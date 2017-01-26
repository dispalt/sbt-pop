package com.dispalt.fwatch.sbt.server;

public interface ServerWithStop {

  /**
   * Stop the server.
   */
  public void stop();

  /**
   * Get the address of the server.
   *
   * @return The address of the server.
   */
  public java.net.InetSocketAddress mainAddress();

}
