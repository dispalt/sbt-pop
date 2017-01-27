/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.pop;

public interface Base {

    void start(ClassLoader cl, int port);

    void stop();

    void restart();
}
