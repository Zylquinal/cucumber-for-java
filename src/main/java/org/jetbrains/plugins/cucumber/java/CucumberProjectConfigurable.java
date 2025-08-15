package org.jetbrains.plugins.cucumber.java;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PackageChooserDialog;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class CucumberProjectConfigurable implements Configurable {

  private final Project myProject;
  private final CucumberPackageFilterService mySettingsService;

  private final DefaultListModel<String> myListModel = new DefaultListModel<>();
  private final JBList<String> myPackageList = new JBList<>(myListModel);

  private EditorTextField myRunnerClassEditor;
  private JBLabel myWarningLabel;

  public CucumberProjectConfigurable(@NotNull Project project) {
    myProject = project;
    mySettingsService = CucumberPackageFilterService.getInstance(project);
  }

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "Cucumber Settings";
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    myRunnerClassEditor = new EditorTextField();

    myWarningLabel = new JBLabel(AllIcons.General.Warning);
    myWarningLabel.setVisible(false);

    myRunnerClassEditor.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        validateRunnerClass();
      }
    });

    JPanel runnerPanel = new JPanel(new BorderLayout());
    runnerPanel.add(myRunnerClassEditor, BorderLayout.CENTER);
    runnerPanel.add(myWarningLabel, BorderLayout.EAST);

    ToolbarDecorator decorator = ToolbarDecorator.createDecorator(myPackageList);
    decorator.setAddAction(button -> {
      PackageChooserDialog chooser = new PackageChooserDialog("Select Package to Scan", myProject);
      if (chooser.showAndGet()) {
        PsiPackage selectedPackage = chooser.getSelectedPackage();
        if (selectedPackage != null) {
          myListModel.addElement(selectedPackage.getQualifiedName());
        }
      }
    });

    return FormBuilder.createFormBuilder()
        .addLabeledComponent("Default Cucumber Runner Class:", runnerPanel)
        .addTooltip("Enter the fully qualified name of the default test runner class.")
        .addVerticalGap(10)
        .addLabeledComponent("Scan for step definitions only in these packages (and sub-packages):", decorator.createPanel(), true)
        .addVerticalGap(5)
        .addTooltip("If this list is empty, the entire project and its dependencies will be scanned.")
        .getPanel();
  }

  private void validateRunnerClass() {
    String className = myRunnerClassEditor.getText();
    if (StringUtil.isEmpty(className)) {
      myWarningLabel.setVisible(false);
      return;
    }

    ReadAction.nonBlocking(() -> {
          GlobalSearchScope scope = GlobalSearchScope.allScope(myProject);
          PsiClass psiClass = JavaPsiFacade.getInstance(myProject).findClass(className, scope);
          return psiClass != null && psiClass.hasAnnotation("io.cucumber.junit.CucumberOptions");
        })
        .finishOnUiThread(ModalityState.stateForComponent(myRunnerClassEditor), (Boolean isValid) -> {
          if (!isValid) {
            myWarningLabel.setVisible(true);
            myWarningLabel.setToolTipText("Class not found or is not a valid Cucumber runner (missing @CucumberOptions annotation)");
          } else {
            myWarningLabel.setVisible(false);
            myWarningLabel.setToolTipText(null);
          }
        })
        .submit(AppExecutorUtil.getAppExecutorService());
  }

  @Override
  public boolean isModified() {
    List<String> uiList = Collections.list(myListModel.elements());
    List<String> settingsList = mySettingsService.getState().packageNames;
    boolean packagesModified = !uiList.equals(settingsList);

    String uiRunner = myRunnerClassEditor.getText();
    String settingsRunner = StringUtil.notNullize(mySettingsService.getState().cucumberRunner);
    boolean runnerModified = !Objects.equals(uiRunner, settingsRunner);

    return packagesModified || runnerModified;
  }

  @Override
  public void apply() {
    mySettingsService.getState().packageNames = Collections.list(myListModel.elements());
    mySettingsService.getState().cucumberRunner = myRunnerClassEditor.getText();
  }

  @Override
  public void reset() {
    myListModel.clear();
    List<String> packageNames = mySettingsService.getState().packageNames;
    if (packageNames != null) {
      for (String name : packageNames) {
        myListModel.addElement(name);
      }
    }
    myRunnerClassEditor.setText(mySettingsService.getState().cucumberRunner);
    validateRunnerClass();
  }
}
