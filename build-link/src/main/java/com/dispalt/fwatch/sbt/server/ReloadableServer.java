/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch.sbt.server;

import java.net.InetSocketAddress;

/**
 * A server that can reload the running application.
 */
public abstract class ReloadableServer implements ServerWithStop {
    private final ServerWithStop server;

    public ReloadableServer(ServerWithStop server) {
        this.server = server;
    }

    /** Executes application's reloading.*/
    public abstract void reload();

    public void stop() { server.stop(); }

    public InetSocketAddress mainAddress() { return server.mainAddress(); }
}
