package com.kagaya.kyaputen.server.grpc;

import com.kagaya.kyaputen.core.service.Lifecycle;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public class GRPCServer implements Lifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GRPCServer.class);

    private final Server server;

    public GRPCServer(int port, BindableService... services) {
        ServerBuilder<?> serverBuilder = ServerBuilder.forPort(port);
        Arrays.stream(services).forEach(serverBuilder::addService);
        server = serverBuilder.build();
    }

    @Override
    public void start() throws IOException {
        registerShutdownHook();
        server.start();
        logger.info("grpc: Server started, listening on " + server.getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            logger.info("grpc: server shutting down");
            server.shutdown();
        }
    }

}
