package org.jetbrains.plugins.cucumber.java.settings;

import com.intellij.util.xmlb.annotations.XCollection;
import java.util.ArrayList;
import java.util.List;

/**
 * This class holds the configuration state.
 * IntelliJ will serialize its public fields to XML.
 */
public class CucumberPackageFilterSettingsState {

  @XCollection
  public List<String> packageNames = new ArrayList<>();

  public String cucumberRunner;

}
