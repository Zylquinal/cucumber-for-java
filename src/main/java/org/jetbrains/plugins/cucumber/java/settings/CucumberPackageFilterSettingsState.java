package org.jetbrains.plugins.cucumber.java.settings;

import com.intellij.util.xmlb.annotations.MapAnnotation;
import com.intellij.util.xmlb.annotations.XCollection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class holds the configuration state.
 * IntelliJ will serialize its public fields to XML.
 */
public class CucumberPackageFilterSettingsState {

  @XCollection
  public List<String> packageNames = new ArrayList<>();

  @XCollection
  public List<String> plugins = new ArrayList<>();

  @XCollection
  public List<String> vmOptions = new ArrayList<>();

  public StepDefinitionMatcher stepDefinitionMatcher = StepDefinitionMatcher.TEXT_BASED;

  public String cucumberRunner;

  @MapAnnotation(surroundWithTag = false, entryTagName = "env", keyAttributeName = "name")
  public Map<String, String> envVars = new HashMap<>();

  public enum StepDefinitionMatcher {
    TEXT_BASED("Text-based (faster, less accurate)"),
    PSI_BASED("PSI-based (more accurate, potentially slower)");

    private final String displayName;

    StepDefinitionMatcher(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

}
