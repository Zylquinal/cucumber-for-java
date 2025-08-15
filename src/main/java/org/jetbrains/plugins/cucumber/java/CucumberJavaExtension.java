// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.cucumber.java;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.psi.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.BDDFrameworkType;
import org.jetbrains.plugins.cucumber.StepDefinitionCreator;
import org.jetbrains.plugins.cucumber.java.steps.JavaStepDefinitionCreator;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CucumberJavaExtension extends AbstractCucumberJavaExtension {
  private static final String CUCUMBER_JAVA_5_STEP_DEFINITION_ANNOTATION_CLASS_NAME = "io.cucumber.java.StepDefinitionAnnotation";
  public static final @NonNls String CUCUMBER_RUNTIME_JAVA_STEP_DEF_ANNOTATION = "cucumber.runtime.java.StepDefAnnotation";
  public static final @NonNls String ZUCHINI_RUNTIME_JAVA_STEP_DEF_ANNOTATION = "org.zuchini.annotations.StepAnnotation";
  public static final String[] CUCUMBER_JAVA_STEP_DEFINITION_ANNOTATION_CLASSES =
    new String[]{CUCUMBER_JAVA_5_STEP_DEFINITION_ANNOTATION_CLASS_NAME, CUCUMBER_RUNTIME_JAVA_STEP_DEF_ANNOTATION,
      ZUCHINI_RUNTIME_JAVA_STEP_DEF_ANNOTATION};
  private static final Logger log = LoggerFactory.getLogger(CucumberJavaExtension.class);

  @Override
  public @NotNull BDDFrameworkType getStepFileType() {
    return new BDDFrameworkType(JavaFileType.INSTANCE);
  }

  @Override
  public @NotNull StepDefinitionCreator getStepDefinitionCreator() {
    return new JavaStepDefinitionCreator();
  }

  @Override
  public List<AbstractStepDefinition> loadStepsFor(@Nullable PsiFile featureFile, @NotNull Module module) {
    return CucumberPackageFilterService.getInstance(module.getProject()).getSteps(module);
  }


}
