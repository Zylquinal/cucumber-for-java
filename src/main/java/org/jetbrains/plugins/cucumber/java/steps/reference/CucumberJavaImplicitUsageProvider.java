package org.jetbrains.plugins.cucumber.java.steps.reference;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.CucumberUtil;
import org.jetbrains.plugins.cucumber.java.CucumberJavaUtil;
import org.jetbrains.plugins.cucumber.java.CucumberPackageFilterService;
import org.jetbrains.plugins.cucumber.java.settings.CucumberPackageFilterSettingsState;
import org.jetbrains.plugins.cucumber.java.steps.factory.JavaStepDefinitionFactory;
import org.jetbrains.plugins.cucumber.java.steps.search.CucumberJavaMethodUsageSearcher;
import org.jetbrains.plugins.cucumber.steps.AbstractStepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/// Implementation is copied in large part from [CucumberJavaMethodUsageSearcher].
public final class CucumberJavaImplicitUsageProvider implements ImplicitUsageProvider {
  private static final Logger log =
      LoggerFactory.getLogger(CucumberJavaImplicitUsageProvider.class);

  @Override
  public boolean isImplicitUsage(@NotNull PsiElement element) {
    if (element instanceof PsiClass psiClass) {
      return CucumberJavaUtil.isStepDefinitionClass(psiClass);
    }
    else if (element instanceof PsiMethod method) {
      if (CucumberJavaUtil.isHook(method) || CucumberJavaUtil.isParameterType(method)) return true;
      if (CucumberJavaUtil.isStepDefinition(method)) {
        final var settingsService = CucumberPackageFilterService.getInstance(element.getProject());
        final var matcher = settingsService.getState().stepDefinitionMatcher;

        final Ref<@Nullable PsiReference> psiReferenceRef = new Ref<>(null);
        Processor<PsiReference> processor = (PsiReference psiReference) -> {
          if (psiReference == null) return true;
          psiReferenceRef.set(psiReference);
          return false;
        };

        final List<PsiAnnotation> stepAnnotations = CucumberJavaUtil.getCucumberStepAnnotations(method);
        for (final PsiAnnotation stepAnnotation : stepAnnotations) {
          if (matcher == CucumberPackageFilterSettingsState.StepDefinitionMatcher.PSI_BASED) {
            var module = ModuleUtilCore.findModuleForPsiElement(element);
            if (module == null) continue;

            String annotationValue = CucumberJavaUtil.getAnnotationValue(stepAnnotation);
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

            CucumberUtil.findGherkinReferencesToElementByPsi(element, stepDefinition, processor, element.getResolveScope());
          } else {
            final String regexp = CucumberJavaUtil.getPatternFromStepDefinition(stepAnnotation, false);
            if (regexp == null) {
              continue;
            }
            CucumberUtil.findGherkinReferencesToElement(method, regexp, processor, method.getResolveScope());
          }

          if (psiReferenceRef.get() != null) return true;
        }
      }
    }

    return false;
  }

  @Override
  public boolean isImplicitRead(@NotNull PsiElement element) {
    return false;
  }

  @Override
  public boolean isImplicitWrite(@NotNull PsiElement element) {
    return false;
  }
}
