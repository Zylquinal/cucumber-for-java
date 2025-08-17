// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.cucumber.java.steps.search;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.CucumberUtil;
import org.jetbrains.plugins.cucumber.java.CucumberJavaUtil;
import org.jetbrains.plugins.cucumber.java.CucumberPackageFilterService;
import org.jetbrains.plugins.cucumber.java.settings.CucumberPackageFilterSettingsState;
import org.jetbrains.plugins.cucumber.java.steps.factory.JavaStepDefinitionFactory;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/// Handles [ReferencesSearch] requests made by [CucumberJavaMethodUsageSearcher].
public final class CucumberJavaStepDefinitionSearch implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
  @Override
  public boolean execute(@NotNull ReferencesSearch.SearchParameters queryParameters, @NotNull Processor<? super PsiReference> consumer) {
    final PsiElement elementToSearch = queryParameters.getElementToSearch();
    if (!(elementToSearch instanceof PsiMethod method)) return true;

    if (!ReadAction.compute(() -> CucumberJavaUtil.isStepDefinition(method))) {
      return true;
    }

    final CucumberPackageFilterService settingsService = CucumberPackageFilterService.getInstance(elementToSearch.getProject());
    final CucumberPackageFilterSettingsState.StepDefinitionMatcher matcher = settingsService.getState().stepDefinitionMatcher;

    final List<PsiAnnotation> stepAnnotations = ReadAction.compute(() -> CucumberJavaUtil.getCucumberStepAnnotations(method));
    for (final PsiAnnotation stepAnnotation : stepAnnotations) {
      boolean continueSearch;
      if (matcher == CucumberPackageFilterSettingsState.StepDefinitionMatcher.PSI_BASED) {
        var module = ReadAction.compute(() -> ModuleUtilCore.findModuleForPsiElement(elementToSearch));
        if (module == null) {
          final var modules = ModuleManager.getInstance(elementToSearch.getProject()).getModules();
          if (modules.length > 0) {
            module = modules[0];

            String annotationValue = ReadAction.compute(() -> CucumberJavaUtil.getAnnotationValue(stepAnnotation));
            if (annotationValue == null) continue;

            AbstractStepDefinition stepDefinition = JavaStepDefinitionFactory.getInstance(module).buildStepDefinition(method, module, annotationValue);
            continueSearch = CucumberUtil.findGherkinReferencesToElementByPsi(
                elementToSearch, stepDefinition, consumer, queryParameters.getEffectiveSearchScope()
            );
          } else {
            return true;
          }
        } else {
          String annotationValue = ReadAction.compute(() -> CucumberJavaUtil.getAnnotationValue(stepAnnotation));
          if (annotationValue == null) continue;

          if (!(method.getContainingFile() instanceof PsiJavaFile containingFile)) {
            continue;
          }

          var fullClassLocation = containingFile.getVirtualFile().getPath();
          var compositeKey = fullClassLocation + ":" + annotationValue;

          AbstractStepDefinition stepDefinition = settingsService.STEP_DEFINITION_CACHE.getIfPresent(compositeKey);
          if (stepDefinition == null) {
            stepDefinition = JavaStepDefinitionFactory.getInstance(module).buildStepDefinition(method, module, annotationValue);
          }

          continueSearch = CucumberUtil.findGherkinReferencesToElementByPsi(
              elementToSearch, stepDefinition, consumer, queryParameters.getEffectiveSearchScope()
          );
        }

      } else {
        final String regexp = CucumberJavaUtil.getPatternFromStepDefinition(stepAnnotation, false);
        if (regexp == null) {
          continue;
        }
        continueSearch = CucumberUtil.findGherkinReferencesToElement(elementToSearch, regexp, consumer, queryParameters.getEffectiveSearchScope());
      }

      if (!continueSearch) {
        return false;
      }
    }
    return true;
  }
}
