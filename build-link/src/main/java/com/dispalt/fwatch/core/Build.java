/*
 * Copyright (C) 2017 Dan Di Spaltro
 */
package com.dispalt.fwatch.core;

import com.dispalt.fwatch.PlayException;
import com.dispalt.fwatch.UsefulException;
import com.dispalt.fwatch.sbt.server.ServerWithStop;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;


public class Build {

  public static final List<String> sharedClasses;
  static {
    List<String> list = new ArrayList<String>();
    list.add(BuildLink.class.getName());
    list.add(ServerWithStop.class.getName());
    list.add(UsefulException.class.getName());
    list.add(PlayException.class.getName());
    list.add(PlayException.InterestingLines.class.getName());
    list.add(PlayException.RichDescription.class.getName());
    list.add(PlayException.ExceptionSource.class.getName());
    list.add(PlayException.ExceptionAttachment.class.getName());
    list.add(com.dispalt.fwatch.sbt.server.ReloadableServer.class.getName());
    sharedClasses = Collections.unmodifiableList(list);
  }

}
