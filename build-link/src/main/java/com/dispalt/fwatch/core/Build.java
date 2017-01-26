
package com.dispalt.fwatch.core;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;


public class Build {

  public static final List<String> sharedClasses;
  static {
    List<String> list = new ArrayList<String>();
    list.add(com.dispalt.fwatch.sbt.server.ReloadableServer.class.getName());
    sharedClasses = Collections.unmodifiableList(list);
  }

}
