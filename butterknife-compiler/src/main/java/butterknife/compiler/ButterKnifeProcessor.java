package butterknife.compiler;

import butterknife.BindArray;
import butterknife.BindBitmap;
import butterknife.BindBool;
import butterknife.BindColor;
import butterknife.BindDimen;
import butterknife.BindDrawable;
import butterknife.BindInt;
import butterknife.BindString;
import butterknife.BindView;
import butterknife.BindViews;
import butterknife.OnCheckedChanged;
import butterknife.OnClick;
import butterknife.OnEditorAction;
import butterknife.OnFocusChange;
import butterknife.OnItemClick;
import butterknife.OnItemLongClick;
import butterknife.OnItemSelected;
import butterknife.OnLongClick;
import butterknife.OnPageChange;
import butterknife.OnTextChanged;
import butterknife.OnTouch;
import butterknife.Optional;
import butterknife.internal.ListenerClass;
import butterknife.internal.ListenerMethod;
import com.google.auto.common.SuperficialValidation;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.ElementKind.METHOD;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.tools.Diagnostic.Kind.ERROR;

@AutoService(Processor.class)
public final class ButterKnifeProcessor extends AbstractProcessor {
  static final Id NO_ID = new Id(-1);
  static final String VIEW_TYPE = "android.view.View";
  private static final String COLOR_STATE_LIST_TYPE = "android.content.res.ColorStateList";
  private static final String BITMAP_TYPE = "android.graphics.Bitmap";
  private static final String DRAWABLE_TYPE = "android.graphics.drawable.Drawable";
  private static final String TYPED_ARRAY_TYPE = "android.content.res.TypedArray";
  private static final String NULLABLE_ANNOTATION_NAME = "Nullable";
  private static final String STRING_TYPE = "java.lang.String";
  private static final String LIST_TYPE = List.class.getCanonicalName();
  private static final String R = "R";
  private static final List<Class<? extends Annotation>> LISTENERS = Arrays.asList(//
      OnCheckedChanged.class, //
      OnClick.class, //
      OnEditorAction.class, //
      OnFocusChange.class, //
      OnItemClick.class, //
      OnItemLongClick.class, //
      OnItemSelected.class, //
      OnLongClick.class, //
      OnPageChange.class, //
      OnTextChanged.class, //
      OnTouch.class //
  );

  private static final List<String> SUPPORTED_TYPES = Arrays.asList(
      "array", "attr", "bool", "color", "dimen", "drawable", "id", "integer", "string"
  );

  private Elements elementUtils;
  private Types typeUtils;
  private Filer filer;
  private Trees trees;

  private final Map<Integer, Id> symbols = new LinkedHashMap<>();

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);

    elementUtils = env.getElementUtils();
    typeUtils = env.getTypeUtils();
    filer = env.getFiler();
    trees = Trees.instance(processingEnv);
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    Set<String> types = new LinkedHashSet<>();
    for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
      types.add(annotation.getCanonicalName());
    }
    return types;
  }

  private Set<Class<? extends Annotation>> getSupportedAnnotations() {
    Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();

    annotations.add(BindArray.class);
    annotations.add(BindBitmap.class);
    annotations.add(BindBool.class);
    annotations.add(BindColor.class);
    annotations.add(BindDimen.class);
    annotations.add(BindDrawable.class);
    annotations.add(BindInt.class);
    annotations.add(BindString.class);
    annotations.add(BindView.class);
    annotations.add(BindViews.class);
    annotations.addAll(LISTENERS);

    return annotations;
  }

  @Override public boolean process(Set<? extends TypeElement> elements, RoundEnvironment env) {
    Map<TypeElement, BindingClass> targetClassMap = findAndParseTargets(env);

    for (Map.Entry<TypeElement, BindingClass> entry : targetClassMap.entrySet()) {
      TypeElement typeElement = entry.getKey();
      BindingClass bindingClass = entry.getValue();

      for (JavaFile javaFile : bindingClass.brewJava()) {
        try {
          javaFile.writeTo(filer);
        } catch (IOException e) {
          error(typeElement, "Unable to write view binder for type %s: %s", typeElement,
              e.getMessage());
        }
      }
    }

    return true;
  }

  private Map<TypeElement, BindingClass> findAndParseTargets(RoundEnvironment env) {
    Map<TypeElement, BindingClass> targetClassMap = new LinkedHashMap<>();
    Set<TypeElement> erasedTargetNames = new LinkedHashSet<>();

    scanForRClasses(env);

    // Process each @BindArray element.
    for (Element element : env.getElementsAnnotatedWith(BindArray.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceArray(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindArray.class, e);
      }
    }

    // Process each @BindBitmap element.
    for (Element element : env.getElementsAnnotatedWith(BindBitmap.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceBitmap(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindBitmap.class, e);
      }
    }

    // Process each @BindBool element.
    for (Element element : env.getElementsAnnotatedWith(BindBool.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceBool(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindBool.class, e);
      }
    }

    // Process each @BindColor element.
    for (Element element : env.getElementsAnnotatedWith(BindColor.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceColor(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindColor.class, e);
      }
    }

    // Process each @BindDimen element.
    for (Element element : env.getElementsAnnotatedWith(BindDimen.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceDimen(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindDimen.class, e);
      }
    }

    // Process each @BindDrawable element.
    for (Element element : env.getElementsAnnotatedWith(BindDrawable.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceDrawable(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindDrawable.class, e);
      }
    }

    // Process each @BindInt element.
    for (Element element : env.getElementsAnnotatedWith(BindInt.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceInt(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindInt.class, e);
      }
    }

    // Process each @BindString element.
    for (Element element : env.getElementsAnnotatedWith(BindString.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseResourceString(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindString.class, e);
      }
    }

    // Process each @BindView element.
    for (Element element : env.getElementsAnnotatedWith(BindView.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseBindView(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindView.class, e);
      }
    }

    // Process each @BindViews element.
    for (Element element : env.getElementsAnnotatedWith(BindViews.class)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseBindViews(element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        logParsingError(element, BindViews.class, e);
      }
    }

    // Process each annotation that corresponds to a listener.
    for (Class<? extends Annotation> listener : LISTENERS) {
      findAndParseListener(env, listener, targetClassMap, erasedTargetNames);
    }

    // Try to find a parent binder for each.
    for (Map.Entry<TypeElement, BindingClass> entry : targetClassMap.entrySet()) {
      TypeElement parentType = findParentType(entry.getKey(), erasedTargetNames);
      if (parentType != null) {
        BindingClass bindingClass = entry.getValue();
        BindingClass parentBindingClass = targetClassMap.get(parentType);
        bindingClass.setParent(parentBindingClass);
      }
    }

    return targetClassMap;
  }

  private void logParsingError(Element element, Class<? extends Annotation> annotation,
      Exception e) {
    StringWriter stackTrace = new StringWriter();
    e.printStackTrace(new PrintWriter(stackTrace));
    error(element, "Unable to parse @%s binding.\n\n%s", annotation.getSimpleName(), stackTrace);
  }

  private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
      String targetThing, Element element) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify method modifiers.
    Set<Modifier> modifiers = element.getModifiers();
    if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
      error(element, "@%s %s must not be private or static. (%s.%s)",
          annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify containing type.
    if (enclosingElement.getKind() != CLASS) {
      error(enclosingElement, "@%s %s may only be contained in classes. (%s.%s)",
          annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify containing class visibility is not private.
    if (enclosingElement.getModifiers().contains(PRIVATE)) {
      error(enclosingElement, "@%s %s may not be contained in private classes. (%s.%s)",
          annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    return hasError;
  }

  private boolean isBindingInWrongPackage(Class<? extends Annotation> annotationClass,
      Element element) {
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
    String qualifiedName = enclosingElement.getQualifiedName().toString();

    if (qualifiedName.startsWith("android.")) {
      error(element, "@%s-annotated class incorrectly in Android framework package. (%s)",
          annotationClass.getSimpleName(), qualifiedName);
      return true;
    }
    if (qualifiedName.startsWith("java.")) {
      error(element, "@%s-annotated class incorrectly in Java framework package. (%s)",
          annotationClass.getSimpleName(), qualifiedName);
      return true;
    }

    return false;
  }

  private void parseBindView(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Start by verifying common generated code restrictions.
    boolean hasError = isInaccessibleViaGeneratedCode(BindView.class, "fields", element)
        || isBindingInWrongPackage(BindView.class, element);

    // Verify that the target type extends from View.
    TypeMirror elementType = element.asType();
    if (elementType.getKind() == TypeKind.TYPEVAR) {
      TypeVariable typeVariable = (TypeVariable) elementType;
      elementType = typeVariable.getUpperBound();
    }
    if (!isSubtypeOfType(elementType, VIEW_TYPE) && !isInterface(elementType)) {
      error(element, "@%s fields must extend from View or be an interface. (%s.%s)",
          BindView.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    int id = element.getAnnotation(BindView.class).value();

    BindingClass bindingClass = targetClassMap.get(enclosingElement);
    if (bindingClass != null) {
      ViewBindings viewBindings = bindingClass.getViewBinding(getId(id));
      if (viewBindings != null && viewBindings.getFieldBinding() != null) {
        FieldViewBinding existingBinding = viewBindings.getFieldBinding();
        error(element, "Attempt to use @%s for an already bound ID %d on '%s'. (%s.%s)",
            BindView.class.getSimpleName(), id, existingBinding.getName(),
            enclosingElement.getQualifiedName(), element.getSimpleName());
        return;
      }
    } else {
      bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    }

    String name = element.getSimpleName().toString();
    TypeName type = TypeName.get(elementType);
    boolean required = isFieldRequired(element);

    FieldViewBinding binding = new FieldViewBinding(name, type, required);
    bindingClass.addField(getId(id), binding);

    // Add the type-erased version to the valid binding targets set.
    erasedTargetNames.add(enclosingElement);
  }

  private void parseBindViews(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Start by verifying common generated code restrictions.
    boolean hasError = isInaccessibleViaGeneratedCode(BindViews.class, "fields", element)
        || isBindingInWrongPackage(BindViews.class, element);

    // Verify that the type is a List or an array.
    TypeMirror elementType = element.asType();
    String erasedType = doubleErasure(elementType);
    TypeMirror viewType = null;
    FieldCollectionViewBinding.Kind kind = null;
    if (elementType.getKind() == TypeKind.ARRAY) {
      ArrayType arrayType = (ArrayType) elementType;
      viewType = arrayType.getComponentType();
      kind = FieldCollectionViewBinding.Kind.ARRAY;
    } else if (LIST_TYPE.equals(erasedType)) {
      DeclaredType declaredType = (DeclaredType) elementType;
      List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
      if (typeArguments.size() != 1) {
        error(element, "@%s List must have a generic component. (%s.%s)",
            BindViews.class.getSimpleName(), enclosingElement.getQualifiedName(),
            element.getSimpleName());
        hasError = true;
      } else {
        viewType = typeArguments.get(0);
      }
      kind = FieldCollectionViewBinding.Kind.LIST;
    } else {
      error(element, "@%s must be a List or array. (%s.%s)", BindViews.class.getSimpleName(),
          enclosingElement.getQualifiedName(), element.getSimpleName());
      hasError = true;
    }
    if (viewType != null && viewType.getKind() == TypeKind.TYPEVAR) {
      TypeVariable typeVariable = (TypeVariable) viewType;
      viewType = typeVariable.getUpperBound();
    }

    // Verify that the target type extends from View.
    if (viewType != null && !isSubtypeOfType(viewType, VIEW_TYPE) && !isInterface(viewType)) {
      error(element, "@%s List or array type must extend from View or be an interface. (%s.%s)",
          BindViews.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int[] ids = element.getAnnotation(BindViews.class).value();
    if (ids.length == 0) {
      error(element, "@%s must specify at least one ID. (%s.%s)", BindViews.class.getSimpleName(),
          enclosingElement.getQualifiedName(), element.getSimpleName());
      hasError = true;
    }

    Integer duplicateId = findDuplicate(ids);
    if (duplicateId != null) {
      error(element, "@%s annotation contains duplicate ID %d. (%s.%s)",
          BindViews.class.getSimpleName(), duplicateId, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    if (hasError) {
      return;
    }

    assert viewType != null; // Always false as hasError would have been true.
    TypeName type = TypeName.get(viewType);
    boolean required = isFieldRequired(element);

    List<Id> idVars = new ArrayList<>();
    for (int id : ids) {
      idVars.add(getId(id));
    }

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldCollectionViewBinding binding = new FieldCollectionViewBinding(name, type, kind, required);
    bindingClass.addFieldCollection(idVars, binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceBool(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is bool.
    if (element.asType().getKind() != TypeKind.BOOLEAN) {
      error(element, "@%s field type must be 'boolean'. (%s.%s)",
          BindBool.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindBool.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindBool.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindBool.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name, "getBoolean", false);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceColor(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is int or ColorStateList.
    boolean isColorStateList = false;
    TypeMirror elementType = element.asType();
    if (COLOR_STATE_LIST_TYPE.equals(elementType.toString())) {
      isColorStateList = true;
    } else if (elementType.getKind() != TypeKind.INT) {
      error(element, "@%s field type must be 'int' or 'ColorStateList'. (%s.%s)",
          BindColor.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindColor.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindColor.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindColor.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name,
        isColorStateList ? "getColorStateList" : "getColor", true);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceDimen(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is int or ColorStateList.
    boolean isInt = false;
    TypeMirror elementType = element.asType();
    if (elementType.getKind() == TypeKind.INT) {
      isInt = true;
    } else if (elementType.getKind() != TypeKind.FLOAT) {
      error(element, "@%s field type must be 'int' or 'float'. (%s.%s)",
          BindDimen.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindDimen.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindDimen.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindDimen.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name,
        isInt ? "getDimensionPixelSize" : "getDimension", false);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceBitmap(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is Bitmap.
    if (!BITMAP_TYPE.equals(element.asType().toString())) {
      error(element, "@%s field type must be 'Bitmap'. (%s.%s)",
          BindBitmap.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindBitmap.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindBitmap.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindBitmap.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldBitmapBinding binding = new FieldBitmapBinding(getId(id), name);
    bindingClass.addBitmap(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceDrawable(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is Drawable.
    if (!DRAWABLE_TYPE.equals(element.asType().toString())) {
      error(element, "@%s field type must be 'Drawable'. (%s.%s)",
          BindDrawable.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindDrawable.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindDrawable.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindDrawable.class).value();
    int tint = element.getAnnotation(BindDrawable.class).tint();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldDrawableBinding binding = new FieldDrawableBinding(getId(id), name, getId(tint));
    bindingClass.addDrawable(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceInt(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is int.
    if (element.asType().getKind() != TypeKind.INT) {
      error(element, "@%s field type must be 'int'. (%s.%s)", BindInt.class.getSimpleName(),
          enclosingElement.getQualifiedName(), element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindInt.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindInt.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindInt.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name, "getInteger", false);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceString(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is String.
    if (!STRING_TYPE.equals(element.asType().toString())) {
      error(element, "@%s field type must be 'String'. (%s.%s)",
          BindString.class.getSimpleName(), enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindString.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindString.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindString.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name, "getString", false);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  private void parseResourceArray(Element element, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    boolean hasError = false;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Verify that the target type is supported.
    String methodName = getArrayResourceMethodName(element);
    if (methodName == null) {
      error(element,
          "@%s field type must be one of: String[], int[], CharSequence[], %s. (%s.%s)",
          BindArray.class.getSimpleName(), TYPED_ARRAY_TYPE, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    // Verify common generated code restrictions.
    hasError |= isInaccessibleViaGeneratedCode(BindArray.class, "fields", element);
    hasError |= isBindingInWrongPackage(BindArray.class, element);

    if (hasError) {
      return;
    }

    // Assemble information on the field.
    String name = element.getSimpleName().toString();
    int id = element.getAnnotation(BindArray.class).value();

    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    FieldResourceBinding binding = new FieldResourceBinding(getId(id), name, methodName, false);
    bindingClass.addResource(binding);

    erasedTargetNames.add(enclosingElement);
  }

  /**
   * Returns a method name from the {@link android.content.res.Resources} class for array resource
   * binding, null if the element type is not supported.
   */
  private static String getArrayResourceMethodName(Element element) {
    TypeMirror typeMirror = element.asType();
    if (TYPED_ARRAY_TYPE.equals(typeMirror.toString())) {
      return "obtainTypedArray";
    }
    if (TypeKind.ARRAY.equals(typeMirror.getKind())) {
      ArrayType arrayType = (ArrayType) typeMirror;
      String componentType = arrayType.getComponentType().toString();
      if (STRING_TYPE.equals(componentType)) {
        return "getStringArray";
      } else if ("int".equals(componentType)) {
        return "getIntArray";
      } else if ("java.lang.CharSequence".equals(componentType)) {
        return "getTextArray";
      }
    }
    return null;
  }

  /** Returns the first duplicate element inside an array, null if there are no duplicates. */
  private static Integer findDuplicate(int[] array) {
    Set<Integer> seenElements = new LinkedHashSet<>();

    for (int element : array) {
      if (!seenElements.add(element)) {
        return element;
      }
    }

    return null;
  }

  /** Uses both {@link Types#erasure} and string manipulation to strip any generic types. */
  private String doubleErasure(TypeMirror elementType) {
    String name = typeUtils.erasure(elementType).toString();
    int typeParamStart = name.indexOf('<');
    if (typeParamStart != -1) {
      name = name.substring(0, typeParamStart);
    }
    return name;
  }

  private void findAndParseListener(RoundEnvironment env,
      Class<? extends Annotation> annotationClass, Map<TypeElement, BindingClass> targetClassMap,
      Set<TypeElement> erasedTargetNames) {
    for (Element element : env.getElementsAnnotatedWith(annotationClass)) {
      if (!SuperficialValidation.validateElement(element)) continue;
      try {
        parseListenerAnnotation(annotationClass, element, targetClassMap, erasedTargetNames);
      } catch (Exception e) {
        StringWriter stackTrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stackTrace));

        error(element, "Unable to generate view binder for @%s.\n\n%s",
            annotationClass.getSimpleName(), stackTrace.toString());
      }
    }
  }

  private void parseListenerAnnotation(Class<? extends Annotation> annotationClass, Element element,
      Map<TypeElement, BindingClass> targetClassMap, Set<TypeElement> erasedTargetNames)
      throws Exception {
    // This should be guarded by the annotation's @Target but it's worth a check for safe casting.
    if (!(element instanceof ExecutableElement) || element.getKind() != METHOD) {
      throw new IllegalStateException(
          String.format("@%s annotation must be on a method.", annotationClass.getSimpleName()));
    }

    ExecutableElement executableElement = (ExecutableElement) element;
    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

    // Assemble information on the method.
    Annotation annotation = element.getAnnotation(annotationClass);
    Method annotationValue = annotationClass.getDeclaredMethod("value");
    if (annotationValue.getReturnType() != int[].class) {
      throw new IllegalStateException(
          String.format("@%s annotation value() type not int[].", annotationClass));
    }

    int[] ids = (int[]) annotationValue.invoke(annotation);
    String name = executableElement.getSimpleName().toString();
    boolean required = isListenerRequired(executableElement);

    // Verify that the method and its containing class are accessible via generated code.
    boolean hasError = isInaccessibleViaGeneratedCode(annotationClass, "methods", element);
    hasError |= isBindingInWrongPackage(annotationClass, element);

    Integer duplicateId = findDuplicate(ids);
    if (duplicateId != null) {
      error(element, "@%s annotation for method contains duplicate ID %d. (%s.%s)",
          annotationClass.getSimpleName(), duplicateId, enclosingElement.getQualifiedName(),
          element.getSimpleName());
      hasError = true;
    }

    ListenerClass listener = annotationClass.getAnnotation(ListenerClass.class);
    if (listener == null) {
      throw new IllegalStateException(
          String.format("No @%s defined on @%s.", ListenerClass.class.getSimpleName(),
              annotationClass.getSimpleName()));
    }

    for (int id : ids) {
      if (id == NO_ID.value) {
        if (ids.length == 1) {
          if (!required) {
            error(element, "ID-free binding must not be annotated with @Optional. (%s.%s)",
                enclosingElement.getQualifiedName(), element.getSimpleName());
            hasError = true;
          }

          // Verify target type is valid for a binding without an id.
          String targetType = listener.targetType();
          if (!isSubtypeOfType(enclosingElement.asType(), targetType)
              && !isInterface(enclosingElement.asType())) {
            error(element, "@%s annotation without an ID may only be used with an object of type "
                    + "\"%s\" or an interface. (%s.%s)",
                annotationClass.getSimpleName(), targetType,
                enclosingElement.getQualifiedName(), element.getSimpleName());
            hasError = true;
          }
        } else {
          error(element, "@%s annotation contains invalid ID %d. (%s.%s)",
              annotationClass.getSimpleName(), id, enclosingElement.getQualifiedName(),
              element.getSimpleName());
          hasError = true;
        }
      }
    }

    ListenerMethod method;
    ListenerMethod[] methods = listener.method();
    if (methods.length > 1) {
      throw new IllegalStateException(String.format("Multiple listener methods specified on @%s.",
          annotationClass.getSimpleName()));
    } else if (methods.length == 1) {
      if (listener.callbacks() != ListenerClass.NONE.class) {
        throw new IllegalStateException(
            String.format("Both method() and callback() defined on @%s.",
                annotationClass.getSimpleName()));
      }
      method = methods[0];
    } else {
      Method annotationCallback = annotationClass.getDeclaredMethod("callback");
      Enum<?> callback = (Enum<?>) annotationCallback.invoke(annotation);
      Field callbackField = callback.getDeclaringClass().getField(callback.name());
      method = callbackField.getAnnotation(ListenerMethod.class);
      if (method == null) {
        throw new IllegalStateException(
            String.format("No @%s defined on @%s's %s.%s.", ListenerMethod.class.getSimpleName(),
                annotationClass.getSimpleName(), callback.getDeclaringClass().getSimpleName(),
                callback.name()));
      }
    }

    // Verify that the method has equal to or less than the number of parameters as the listener.
    List<? extends VariableElement> methodParameters = executableElement.getParameters();
    if (methodParameters.size() > method.parameters().length) {
      error(element, "@%s methods can have at most %s parameter(s). (%s.%s)",
          annotationClass.getSimpleName(), method.parameters().length,
          enclosingElement.getQualifiedName(), element.getSimpleName());
      hasError = true;
    }

    // Verify method return type matches the listener.
    TypeMirror returnType = executableElement.getReturnType();
    if (returnType instanceof TypeVariable) {
      TypeVariable typeVariable = (TypeVariable) returnType;
      returnType = typeVariable.getUpperBound();
    }
    if (!returnType.toString().equals(method.returnType())) {
      error(element, "@%s methods must have a '%s' return type. (%s.%s)",
          annotationClass.getSimpleName(), method.returnType(),
          enclosingElement.getQualifiedName(), element.getSimpleName());
      hasError = true;
    }

    if (hasError) {
      return;
    }

    Parameter[] parameters = Parameter.NONE;
    if (!methodParameters.isEmpty()) {
      parameters = new Parameter[methodParameters.size()];
      BitSet methodParameterUsed = new BitSet(methodParameters.size());
      String[] parameterTypes = method.parameters();
      for (int i = 0; i < methodParameters.size(); i++) {
        VariableElement methodParameter = methodParameters.get(i);
        TypeMirror methodParameterType = methodParameter.asType();
        if (methodParameterType instanceof TypeVariable) {
          TypeVariable typeVariable = (TypeVariable) methodParameterType;
          methodParameterType = typeVariable.getUpperBound();
        }

        for (int j = 0; j < parameterTypes.length; j++) {
          if (methodParameterUsed.get(j)) {
            continue;
          }
          if (isSubtypeOfType(methodParameterType, parameterTypes[j])
              || isInterface(methodParameterType)) {
            parameters[i] = new Parameter(j, TypeName.get(methodParameterType));
            methodParameterUsed.set(j);
            break;
          }
        }
        if (parameters[i] == null) {
          StringBuilder builder = new StringBuilder();
          builder.append("Unable to match @")
              .append(annotationClass.getSimpleName())
              .append(" method arguments. (")
              .append(enclosingElement.getQualifiedName())
              .append('.')
              .append(element.getSimpleName())
              .append(')');
          for (int j = 0; j < parameters.length; j++) {
            Parameter parameter = parameters[j];
            builder.append("\n\n  Parameter #")
                .append(j + 1)
                .append(": ")
                .append(methodParameters.get(j).asType().toString())
                .append("\n    ");
            if (parameter == null) {
              builder.append("did not match any listener parameters");
            } else {
              builder.append("matched listener parameter #")
                  .append(parameter.getListenerPosition() + 1)
                  .append(": ")
                  .append(parameter.getType());
            }
          }
          builder.append("\n\nMethods may have up to ")
              .append(method.parameters().length)
              .append(" parameter(s):\n");
          for (String parameterType : method.parameters()) {
            builder.append("\n  ").append(parameterType);
          }
          builder.append(
              "\n\nThese may be listed in any order but will be searched for from top to bottom.");
          error(executableElement, builder.toString());
          return;
        }
      }
    }

    MethodViewBinding binding = new MethodViewBinding(name, Arrays.asList(parameters), required);
    BindingClass bindingClass = getOrCreateTargetClass(targetClassMap, enclosingElement);
    for (int id : ids) {
      if (!bindingClass.addMethod(getId(id), listener, method, binding)) {
        error(element, "Multiple listener methods with return value specified for ID %d. (%s.%s)",
            id, enclosingElement.getQualifiedName(), element.getSimpleName());
        return;
      }
    }

    // Add the type-erased version to the valid binding targets set.
    erasedTargetNames.add(enclosingElement);
  }

  private boolean isInterface(TypeMirror typeMirror) {
    return typeMirror instanceof DeclaredType
        && ((DeclaredType) typeMirror).asElement().getKind() == INTERFACE;
  }

  private boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
    if (otherType.equals(typeMirror.toString())) {
      return true;
    }
    if (typeMirror.getKind() != TypeKind.DECLARED) {
      return false;
    }
    DeclaredType declaredType = (DeclaredType) typeMirror;
    List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
    if (typeArguments.size() > 0) {
      StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
      typeString.append('<');
      for (int i = 0; i < typeArguments.size(); i++) {
        if (i > 0) {
          typeString.append(',');
        }
        typeString.append('?');
      }
      typeString.append('>');
      if (typeString.toString().equals(otherType)) {
        return true;
      }
    }
    Element element = declaredType.asElement();
    if (!(element instanceof TypeElement)) {
      return false;
    }
    TypeElement typeElement = (TypeElement) element;
    TypeMirror superType = typeElement.getSuperclass();
    if (isSubtypeOfType(superType, otherType)) {
      return true;
    }
    for (TypeMirror interfaceType : typeElement.getInterfaces()) {
      if (isSubtypeOfType(interfaceType, otherType)) {
        return true;
      }
    }
    return false;
  }

  private BindingClass getOrCreateTargetClass(Map<TypeElement, BindingClass> targetClassMap,
      TypeElement enclosingElement) {
    BindingClass bindingClass = targetClassMap.get(enclosingElement);
    if (bindingClass == null) {
      TypeName targetType = TypeName.get(enclosingElement.asType());
      if (targetType instanceof ParameterizedTypeName) {
        targetType = ((ParameterizedTypeName) targetType).rawType;
      }

      String packageName = getPackageName(enclosingElement);
      String className = getClassName(enclosingElement, packageName);
      ClassName binderClassName = ClassName.get(packageName, className + "_ViewBinder");
      ClassName unbinderClassName = ClassName.get(packageName, className + "_ViewBinding");

      boolean isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);

      bindingClass = new BindingClass(targetType, binderClassName, unbinderClassName, isFinal);
      targetClassMap.put(enclosingElement, bindingClass);
    }
    return bindingClass;
  }

  private static String getClassName(TypeElement type, String packageName) {
    int packageLen = packageName.length() + 1;
    return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
  }

  /** Finds the parent binder type in the supplied set, if any. */
  private TypeElement findParentType(TypeElement typeElement, Set<TypeElement> parents) {
    TypeMirror type;
    while (true) {
      type = typeElement.getSuperclass();
      if (type.getKind() == TypeKind.NONE) {
        return null;
      }
      typeElement = (TypeElement) ((DeclaredType) type).asElement();
      if (parents.contains(typeElement)) {
        return typeElement;
      }
    }
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  private void error(Element element, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    processingEnv.getMessager().printMessage(ERROR, message, element);
  }

  private String getPackageName(TypeElement type) {
    return elementUtils.getPackageOf(type).getQualifiedName().toString();
  }

  private static boolean hasAnnotationWithName(Element element, String simpleName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
      if (simpleName.equals(annotationName)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFieldRequired(Element element) {
    return !hasAnnotationWithName(element, NULLABLE_ANNOTATION_NAME);
  }

  private static boolean isListenerRequired(ExecutableElement element) {
    return element.getAnnotation(Optional.class) == null;
  }

  private static AnnotationMirror getMirror(Element element,
      Class<? extends Annotation> annotation) {
    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      if (annotationMirror.getAnnotationType().toString().equals(annotation.getCanonicalName())) {
        return annotationMirror;
      }
    }
    return null;
  }

  private Id getId(int id) {
    if (symbols.get(id) == null) {
      symbols.put(id, new Id(id));
    }
    return symbols.get(id);
  }

  private void scanForRClasses(RoundEnvironment env) {
    RClassScanner scanner = new RClassScanner();

    for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
      for (Element element : env.getElementsAnnotatedWith(annotation)) {
        JCTree tree = (JCTree) trees.getTree(element, getMirror(element, annotation));
        tree.accept(scanner);
      }
    }

    for (String rClass : scanner.getRClasses()) {
      parseRClass(rClass);
    }
  }

  private void parseRClass(String rClass) {
    Element element;

    try {
      element = elementUtils.getTypeElement(rClass);
    } catch (MirroredTypeException mte) {
      element = typeUtils.asElement(mte.getTypeMirror());
    }

    JCTree tree = (JCTree) trees.getTree(element);
    if (tree != null) { // tree can be null if the references are compiled types and not source
      IdScanner idScanner =
          new IdScanner(symbols, elementUtils.getPackageOf(element).getQualifiedName().toString());
      tree.accept(idScanner);
    } else {
      parseCompiledR((TypeElement) element);
    }
  }

  private void parseCompiledR(TypeElement rClass) {
    for (Element element : rClass.getEnclosedElements()) {
      String innerClassName = element.getSimpleName().toString();
      if (SUPPORTED_TYPES.contains(innerClassName)) {
        for (Element enclosedElement : element.getEnclosedElements()) {
          if (enclosedElement instanceof VariableElement) {
            VariableElement variableElement = (VariableElement) enclosedElement;
            Object value = variableElement.getConstantValue();

            if (value instanceof Integer) {
              int id = (Integer) value;
              ClassName rClassName =
                  ClassName.get(elementUtils.getPackageOf(variableElement).toString(), "R",
                      innerClassName);
              String resourceName = variableElement.getSimpleName().toString();
              symbols.put(id, new Id(id, rClassName, resourceName));
            }
          }
        }
      }
    }
  }

  private static class RClassScanner extends TreeScanner {
    private final Set<String> rClasses = new LinkedHashSet<>();

    @Override public void visitSelect(JCTree.JCFieldAccess jcFieldAccess) {
      Symbol symbol = jcFieldAccess.sym;
      if (symbol != null
          && symbol.getEnclosingElement() != null
          && symbol.getEnclosingElement().getEnclosingElement() != null
          && symbol.getEnclosingElement().getEnclosingElement().enclClass() != null) {
        rClasses.add(symbol.getEnclosingElement().getEnclosingElement().enclClass().className());
      }
    }

    Set<String> getRClasses() {
      return rClasses;
    }
  }

  private static class IdScanner extends TreeScanner {
    private final Map<Integer, Id> ids;
    private final String packageName;

    IdScanner(Map<Integer, Id> ids, String packageName) {
      this.ids = ids;
      this.packageName = packageName;
    }

    @Override public void visitClassDef(JCTree.JCClassDecl jcClassDecl) {
      for (JCTree tree : jcClassDecl.defs) {
        if (tree instanceof ClassTree) {
          ClassTree classTree = (ClassTree) tree;
          String className = classTree.getSimpleName().toString();
          if (SUPPORTED_TYPES.contains(className)) {
            ClassName rClassName = ClassName.get(packageName, "R", className);
            VarScanner scanner = new VarScanner(ids, rClassName);
            ((JCTree) classTree).accept(scanner);
          }
        }
      }
    }
  }

  private static class VarScanner extends TreeScanner {
    private final Map<Integer, Id> ids;
    private final ClassName className;

    private VarScanner(Map<Integer, Id> ids, ClassName className) {
      this.ids = ids;
      this.className = className;
    }

    @Override public void visitVarDef(JCTree.JCVariableDecl jcVariableDecl) {
      if ("int".equals(jcVariableDecl.getType().toString())) {
        int id = Integer.valueOf(jcVariableDecl.getInitializer().toString());
        String resourceName = jcVariableDecl.getName().toString();
        ids.put(id, new Id(id, className, resourceName));
      }
    }
  }
}
