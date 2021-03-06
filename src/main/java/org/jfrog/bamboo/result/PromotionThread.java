package org.jfrog.bamboo.result;

import com.atlassian.bamboo.variable.VariableDefinitionContext;
import com.atlassian.bamboo.variable.VariableDefinitionManager;
import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.map.introspect.JacksonAnnotationIntrospector;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.builder.PromotionBuilder;
import org.jfrog.build.client.ArtifactoryBuildInfoClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.jfrog.bamboo.result.ViewReleaseManagementAction.*;

/**
 * Executes the promotion process
 *
 * @author Noam Y. Tenne
 */
public class PromotionThread extends Thread {

    transient Logger log = Logger.getLogger(PromotionThread.class);

    private ViewReleaseManagementAction action;
    private ArtifactoryBuildInfoClient client;
    private String bambooUsername;

    public PromotionThread(ViewReleaseManagementAction action, ArtifactoryBuildInfoClient client,
            String bambooUsername) {
        this.action = action;
        this.client = client;
        this.bambooUsername = bambooUsername;
    }

    @Override
    public void run() {
        try {
            promotionAction.getLock().lock();
            promotionAction.setBuildKey(action.getBuildKey());
            promotionAction.setBuildNumber(action.getBuildNumber());
            promotionAction.setDone(false);
            promotionAction.getLog().clear();

            boolean pluginExecutedSuccessfully = !PROMOTION_PUSH_TO_NEXUS_MODE.equals(action.getPromotionMode()) ||
                    executePushToNexusPlugin();

            if (pluginExecutedSuccessfully) {
                performPromotion();
            }
        } catch (Exception e) {
            String message = "An error occurred: " + e.getMessage();
            logErrorToUiAndLogger(message, e);
        } finally {
            try {
                client.shutdown();
            } finally {
                promotionAction.setDone(true);
                promotionAction.getLock().unlock();
            }
        }
    }

    private boolean executePushToNexusPlugin() throws IOException {
        logMessageToUiAndLogger("Executing 'Push to Nexus' plugin ...");
        VariableDefinitionManager varDefManager = action.getVariableDefinitionManager();
        Map<String, VariableDefinitionContext> globalVars = varDefManager
                .getVariableDefinitionMap(action.getPlan(), Maps.<String, String>newHashMap());

        Map<String, String> executeRequestParams = Maps.newHashMap();
        executeRequestParams.put(BuildInfoFields.BUILD_NAME, action.getBuild().getName());
        executeRequestParams.put(BuildInfoFields.BUILD_NUMBER, action.getBuildNumber().toString());
        Map<String, VariableDefinitionContext> nexusPushVars = Maps.filterKeys(globalVars, new Predicate<String>() {
            @Override
            public boolean apply(String input) {
                return StringUtils.isNotBlank(input) && input.startsWith(NEXUS_PUSH_PROPERTY_PREFIX);
            }
        });
        for (Map.Entry<String, VariableDefinitionContext> nexusPushVarEntry : nexusPushVars.entrySet()) {
            executeRequestParams.put(StringUtils.removeStart(nexusPushVarEntry.getKey(), NEXUS_PUSH_PROPERTY_PREFIX),
                    nexusPushVarEntry.getValue().getValue());
        }
        HttpResponse nexusPushResponse = null;
        try {
            nexusPushResponse = client.executeUserPlugin(NEXUS_PUSH_PLUGIN_NAME, executeRequestParams);
            StatusLine responseStatusLine = nexusPushResponse.getStatusLine();
            if (HttpStatus.SC_OK == responseStatusLine.getStatusCode()) {
                logMessageToUiAndLogger("Plugin successfully executed!");
                return true;
            } else {
                String responseContent = entityToString(nexusPushResponse);
                String message = "Plugin execution failed: " + responseStatusLine + "<br/>" + responseContent;
                logErrorToUiAndLogger(message);
                return false;
            }
        } finally {
            if (nexusPushResponse != null) {
                HttpEntity entity = nexusPushResponse.getEntity();
                if (entity != null) {
                    entity.consumeContent();
                }
            }
        }
    }

    private void performPromotion() throws IOException {
        logMessageToUiAndLogger("Promoting build ...");
        // do a dry run first
        PromotionBuilder promotionBuilder = new PromotionBuilder().status(action.getTarget())
                .comment(action.getComment()).ciUser(bambooUsername).targetRepo(action.getPromotionRepo())
                .dependencies(action.isIncludeDependencies()).copy(action.isUseCopy())
                .dryRun(true);
        logMessageToUiAndLogger("Performing dry run promotion (no changes are made during dry run) ...");
        String buildName = action.getBuild().getName();
        String buildNumber = action.getBuildNumber().toString();
        HttpResponse dryResponse = null;
        HttpResponse wetResponse = null;
        try {
            dryResponse = client.stageBuild(buildName, buildNumber, promotionBuilder.build());
            if (checkSuccess(dryResponse, true)) {
                logMessageToUiAndLogger("Dry run finished successfully. Performing promotion ...");
                wetResponse = client.stageBuild(buildName, buildNumber, promotionBuilder.dryRun(false).build());
                if (checkSuccess(wetResponse, false)) {
                    logMessageToUiAndLogger("Promotion completed successfully!");
                }
            }
        } finally {
            if (dryResponse != null) {
                HttpEntity entity = dryResponse.getEntity();
                if (entity != null) {
                    entity.consumeContent();
                }
            }
            if (wetResponse != null) {
                HttpEntity entity = wetResponse.getEntity();
                if (entity != null) {
                    entity.consumeContent();
                }
            }
        }
    }

    /**
     * Checks the status and return true on success
     *
     * @param response
     * @param dryRun
     * @return
     */
    private boolean checkSuccess(HttpResponse response, boolean dryRun) throws IOException {
        StatusLine status = response.getStatusLine();
        String content = entityToString(response);
        if (status.getStatusCode() != 200) {
            if (dryRun) {
                String message = "Promotion failed during dry run (no change in Artifactory was done): " +
                        status + "<br/>" + content;
                logErrorToUiAndLogger(message);
            } else {
                String message = "Promotion failed. View Artifactory logs for more details: " + status +
                        "<br/>" + content;
                logErrorToUiAndLogger(message);
            }
            return false;
        }

        JsonFactory factory = createJsonFactory();
        JsonParser parser = factory.createJsonParser(content);
        JsonNode root = parser.readValueAsTree();
        JsonNode messagesNode = root.get("messages");
        for (JsonNode node : messagesNode) {
            String level = node.get("level").getTextValue();
            String message = node.get("message").getTextValue();
            if (("WARNING".equals(level) || "ERROR".equals(level)) && !message.startsWith("No items were")) {
                String errorMessage = "Received " + level + ": " + message;
                logErrorToUiAndLogger(errorMessage);
                return false;
            }
        }
        return true;
    }

    private void logErrorToUiAndLogger(String message) {
        logErrorToUiAndLogger(message, null);
    }

    private void logErrorToUiAndLogger(String message, Exception e) {
        if (e != null) {
            StringWriter sTStringWriter = new StringWriter();
            PrintWriter sTPrintWriter = new PrintWriter(sTStringWriter);
            e.printStackTrace(sTPrintWriter);
            promotionAction.getLog().add(message + "<br/>" + sTStringWriter.toString());
        } else {
            promotionAction.getLog().add(message + "<br/>");
        }
        log.error(message, e);
    }

    private void logMessageToUiAndLogger(String message) {
        log.info(message);
        promotionAction.getLog().add(message + "<br/>");
    }

    private JsonFactory createJsonFactory() {
        JsonFactory jsonFactory = new JsonFactory();
        ObjectMapper mapper = new ObjectMapper(jsonFactory);
        mapper.getSerializationConfig().setAnnotationIntrospector(new JacksonAnnotationIntrospector());
        mapper.getSerializationConfig().setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
        jsonFactory.setCodec(mapper);
        return jsonFactory;
    }

    private String entityToString(HttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        InputStream is = entity.getContent();
        return IOUtils.toString(is, "UTF-8");
    }
}
