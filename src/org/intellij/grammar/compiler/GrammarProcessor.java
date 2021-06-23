/*
 * Copyright 2011-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.intellij.grammar.compiler;

import com.intellij.lang.LanguageASTFactory;
import com.intellij.lang.LanguageBraceMatching;
import com.intellij.mock.MockProject;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.grammar.*;
import org.intellij.grammar.generator.ParserGenerator;
import org.intellij.grammar.psi.BnfFile;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.*;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

@SupportedAnnotationTypes("*")
@SupportedOptions({GrammarProcessor.GRAMMARS_OPTION})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public final class GrammarProcessor extends AbstractProcessor {
  static final String GRAMMARS_OPTION = "grammars";

  private final Queue<String> myGrammarPaths = new ArrayDeque<>();
  private final Map<String, FileObject> myFiles = new HashMap<>();

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    String[] grammars = StringUtil.notNullize(processingEnv.getOptions().get(GRAMMARS_OPTION)).split(Pattern.quote(File.pathSeparator));
    myGrammarPaths.addAll(ContainerUtil.findAll(grammars, o -> !StringUtil.isEmptyOrSpaces(o)));

    LightPsi.init();
    LightPsi.Init.addKeyedExtension(LanguageASTFactory.INSTANCE, BnfLanguage.INSTANCE, new BnfASTFactory(), null);
    LightPsi.Init.addKeyedExtension(LanguageBraceMatching.INSTANCE, BnfLanguage.INSTANCE, new BnfBraceMatcher(), null);

    PsiFile testPsi = LightPsi.parseFile("test.bnf", "", new BnfParserDefinition());
    if (!(testPsi instanceof BnfFile) || !(testPsi.getProject() instanceof MockProject)) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Grammar parser failed to initialize");
    }
    else {
      MockProject project = (MockProject)testPsi.getProject();
      //project.getPicoContainer().unregisterComponent(JavaHelper.class.getName());
      //project.registerService(JavaHelper.class, new JavacJavaHelper(processingEnv));
    }
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    while (!myGrammarPaths.isEmpty()) {
      try {
        File file = new File(Objects.requireNonNull(myGrammarPaths.poll()));
        PsiFile bnfFile = LightPsi.parseFile(file, new BnfParserDefinition());
        if (bnfFile instanceof BnfFile) {
          new ParserGenerator((BnfFile)bnfFile, file.getParentFile().getCanonicalPath(), "", "") {
            @Override
            protected PrintWriter openOutputInner(String className, File file) throws IOException {
              TypeElement element = processingEnv.getElementUtils().getTypeElement(className);
              Writer writer = element == null ? processingEnv.getFiler().createSourceFile(className).openWriter() :
                              new OutputStreamWriter(OutputStream.nullOutputStream());
              return new PrintWriter(writer);
            }

            @Override
            public void addWarning(String text) {
              processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, text);
            }
          }.generate();
        }
        else {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Not a grammar file: " + file.getCanonicalPath());
        }
      }
      catch (IOException e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.toString());
      }
    }
    return false;
  }
}
