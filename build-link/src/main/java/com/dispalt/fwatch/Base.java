/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch;

public interface Base {

    void start(ClassLoader cl);

    void stop();

    void restart();
}
