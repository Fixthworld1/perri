package org.code13k.perri.service.main;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.StringUtils;
import org.code13k.perri.business.message.MessageManager;
import org.code13k.perri.config.AppConfig;
import org.code13k.perri.config.ChannelConfig;
import org.code13k.perri.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.util.ArrayList;


public class MainHttpServer extends AbstractVerticle {
    // Logger
    private static final Logger mLogger = LoggerFactory.getLogger(MainHttpServer.class);

    // Port
    public static final int PORT = AppConfig.getInstance().getPort().getMainHttp();

    /**
     * start()
     */
    @Override
    public void start() throws Exception {
        super.start();
        mLogger.trace("start()");

        // Init HTTP APIHttpServer
        HttpServerOptions httpServerOptions = new HttpServerOptions();
        httpServerOptions.setCompressionSupported(true);
        httpServerOptions.setPort(PORT);
        httpServerOptions.setIdleTimeout(10); // seconds
        HttpServer httpServer = vertx.createHttpServer(httpServerOptions);

        // Routing
        Router router = Router.router(vertx);
        setRouter(router);

        // Listen
        httpServer.requestHandler(router::accept).listen();
    }

    /**
     * Set router
     */
    private void setRouter(Router router) {
        // GET,POST /:channel?message={메세지내용}&tags={메세지태그}
        router.route().method(HttpMethod.GET).path("/:channel").handler(routingContext -> {
            mLogger.debug("Request host : " + routingContext.request().localAddress().host());
            routingContext.request().endHandler(new Handler<Void>() {
                @Override
                public void handle(Void event) {
                    String channel = routingContext.request().getParam("channel");
                    String message = routingContext.request().getParam("message");
                    String tags = routingContext.request().getParam("tags");
                    sendMessage(routingContext, channel, message, tags);
                }
            });
        });
        router.route().method(HttpMethod.POST).path("/:channel").handler(routingContext -> {
            final String headerContentType = routingContext.request().getHeader(HttpHeaderNames.CONTENT_TYPE);

            // application/json
            if (HttpHeaderValues.APPLICATION_JSON.contentEqualsIgnoreCase(headerContentType)) {
                routingContext.request().bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer event) {
                        String body = event.toString();
                        mLogger.trace("body = " + body);

                        // Process
                        Gson gson = new Gson();
                        JsonObject jsonObject = gson.fromJson(body, JsonObject.class);
                        String channel = routingContext.request().getParam("channel");
                        String message = jsonObject.get("message").getAsString();
                        String tags = jsonObject.get("tags").getAsString();
                        sendMessage(routingContext, channel, message, tags);
                    }
                });
            }

            // multipart/form-data
            else {
                routingContext.request().setExpectMultipart(true);
                routingContext.request().endHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void event) {
                        String channel = routingContext.request().getParam("channel");
                        MultiMap form = routingContext.request().formAttributes();
                        String message = form.get("message");
                        String tags = form.get("tags");
                        sendMessage(routingContext, channel, message, tags);
                    }
                });
            }
        });
    }

    /**
     * Response HTTP 200 OK
     */
    private void responseHttpOK(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response();
        response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
        response.setStatusCode(200);
        response.setStatusMessage("OK");
        response.end("OK");
        response.close();
    }

    /**
     * Response HTTP error status
     */
    private void responseHttpError(RoutingContext routingContext, int statusCode, String message) {
        HttpServerResponse response = routingContext.response();
        response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
        response.setStatusCode(statusCode);
        response.setStatusMessage(message);
        response.end(message);
        response.close();
    }

    /**
     * Send message
     */
    private void sendMessage(RoutingContext routingContext, String channel, String message, String tags) {
        mLogger.debug("channel # " + channel);
        mLogger.debug("message # " + message);
        mLogger.debug("tags # " + tags);

        // Exception (1)
        if (StringUtils.isEmpty(message)) {
            responseHttpError(routingContext, 403, "Invalid message");
            return;
        }

        // Exception (2)
        if (ChannelConfig.getInstance().isAvailableKey(channel) == false) {
            responseHttpError(routingContext, 403, "Invalid channel");
            return;
        }

        // Limit message byte (3K = 3072byte)
        try {
            int maxByte = 3072;
            String urlEncodedMessage = URLEncoder.encode(message, "UTF-8");
            int urlEncodedMessageByte = urlEncodedMessage.getBytes().length;
            if (urlEncodedMessageByte > maxByte) {
                mLogger.info("Message is too long (maximum byte is " + maxByte + "byte)");
                int endIndex = urlEncodedMessageByte - (urlEncodedMessageByte - maxByte);
                message = new String(message.getBytes(), 0, endIndex);
                message += " ...";
            }
        } catch (Exception e) {
            mLogger.error("Failed to limit message", e);
            responseHttpError(routingContext, 500, "Internal server error");
            return;
        }

        // Limit tags byte (512byte)
        ArrayList<String> tagList = new ArrayList<>();
        ArrayList<String> tempTagList = convertTagList(tags);
        try {
            int maxByte = 512;
            int totalByte = 0;
            int idx = 0;
            for (idx = 0; idx < tempTagList.size(); idx++) {
                String urlEncodedTag = tempTagList.get(idx);
                totalByte += urlEncodedTag.getBytes().length;
                if (totalByte > maxByte) {
                    mLogger.info("Tags is too long (maximum byte is " + maxByte + "byte)");
                    break;
                }
                tagList.add(tempTagList.get(idx));
            }
        } catch (Exception e) {
            mLogger.error("Failed to limit tags", e);
            responseHttpError(routingContext, 500, "Internal server error");
            return;
        }
        mLogger.trace("tagList = " + tagList);

        // Process
        Message messageObject = new Message();
        messageObject.setChannel(channel);
        messageObject.setText(message);
        messageObject.setTags(tagList);
        MessageManager.getInstance().send(messageObject);
        responseHttpOK(routingContext);
    }

    /**
     * convert comma separated tag string to array list
     */
    private ArrayList<String> convertTagList(String tags) {
        if (StringUtils.isEmpty(tags) == false) {
            String[] tagArray = StringUtils.split(tags, ",");
            if (tagArray != null && tagArray.length > 0) {
                ArrayList<String> result = new ArrayList<String>();
                for (int i = 0; i < tagArray.length; i++) {
                    String tag = StringUtils.trim(tagArray[i]);
                    if (StringUtils.isEmpty(tag) == false) {
                        result.add(tag);
                    }
                }
                return result;
            }
        }
        return null;
    }
}
