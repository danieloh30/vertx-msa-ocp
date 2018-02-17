package com.redhat.refarch.vertx.lambdaair.sales.service;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uber.jaeger.Configuration;
import com.uber.jaeger.Span;
import io.opentracing.contrib.vertx.ext.web.TracingHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

public class Vertical extends AbstractVerticle {
    private static Logger logger = Logger.getLogger(Vertical.class.getName());

    @Override
    public void start() {
        Json.mapper.registerModule(new JavaTimeModule());
        ConfigStoreOptions store = new ConfigStoreOptions();
        store.setType("file").setFormat("yaml").setConfig(new JsonObject().put("path", "app-config.yml"));
        ConfigRetriever retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(store));
        retriever.getConfig(this::startWithConfig);
    }

    private void startWithConfig(AsyncResult<JsonObject> configResult) {
        if (configResult.failed()) {
            throw new IllegalStateException(configResult.cause());
        }
        mergeIn(null, configResult.result().getMap());

        DeploymentOptions deploymentOptions = new DeploymentOptions().setWorker(true);
        vertx.deployVerticle(new SalesTicketingService(), deploymentOptions, deployResult -> {
            if (deployResult.succeeded()) {
                Router router = Router.router(vertx);
                router.get("/health").handler(routingContext -> routingContext.response().end("OK"));
                router.post("/price").handler(Vertical.this::price);
                setupTracing(router);
                HttpServer httpServer = vertx.createHttpServer();
                httpServer.requestHandler(router::accept);
                int port = config().getInteger("http.port", 8080);
                httpServer.listen(port);
            } else {
                logger.log(Level.SEVERE, "Unable to deploy verticle", deployResult.cause());
            }
        });

    }

    private void mergeIn(String prefix, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key;
            if (prefix == null) {
                key = entry.getKey();
            } else {
                key = prefix + "." + entry.getKey();
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                //noinspection unchecked
                mergeIn(key, (Map<String, Object>) value);
            } else {
                config().put(key, value);
            }
        }
    }

    private void setupTracing(Router router) {
        Configuration.SamplerConfiguration samplerConfiguration = new Configuration.SamplerConfiguration(config().getString("JAEGER_SAMPLER_TYPE"), config().getDouble("JAEGER_SAMPLER_PARAM"), config().getString("JAEGER_SAMPLER_MANAGER_HOST_PORT"));
        Configuration.ReporterConfiguration reporterConfiguration = new Configuration.ReporterConfiguration(config().getBoolean("JAEGER_REPORTER_LOG_SPANS"), config().getString("JAEGER_AGENT_HOST"), config().getInteger("JAEGER_AGENT_PORT"), config().getInteger("JAEGER_REPORTER_FLUSH_INTERVAL"), config().getInteger("JAEGER_REPORTER_MAX_QUEUE_SIZE"));
        Configuration configuration = new Configuration(config().getString("JAEGER_SERVICE_NAME"), samplerConfiguration, reporterConfiguration);
        TracingHandler tracingHandler = new TracingHandler(configuration.getTracer());
        router.route().order(-1).handler(tracingHandler).failureHandler(tracingHandler);
    }

    private void price(RoutingContext routingContext) {
        getActiveSpan(routingContext).setTag("Operation", "Determine Price for a Flight");
        routingContext.request().bodyHandler(buffer -> handleIt(routingContext, buffer));
    }

    private void handleIt(RoutingContext routingContext, Buffer buffer) {
        vertx.eventBus().<String>send(SalesTicketingService.class.getName(), buffer.toString(), asyncResult -> respondPrice(routingContext, asyncResult));
    }


    private void respondPrice(RoutingContext routingContext, AsyncResult<Message<String>> reply) {
        HttpServerResponse response = routingContext.response();
        if (reply.succeeded()) {
            response.putHeader("Content-Type", "application/json; charset=utf-8");
            response.end(reply.result().body());
        } else {
            Throwable exception = reply.cause();
            logger.log(Level.SEVERE, "Failed to price ticket", exception);
            response.setStatusCode(500);
            response.setStatusMessage(exception.getMessage());
            response.end();
        }
    }

    private Span getActiveSpan(RoutingContext routingContext) {
        return routingContext.get(TracingHandler.CURRENT_SPAN);
    }
}
