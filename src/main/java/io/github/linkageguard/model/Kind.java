package io.github.linkageguard.model;

public enum Kind {
    MISSING_CLASS("NoClassDefFoundError"),
    MISSING_METHOD("NoSuchMethodError"),
    MISSING_FIELD("NoSuchFieldError"),
    ABSTRACT_METHOD("AbstractMethodError"),
    INCOMPATIBLE_CHANGE("IncompatibleClassChangeError"),
    ILLEGAL_ACCESS("IllegalAccessError"),
    IMPOSSIBLE_CAST("ClassCastException"),
    DUPLICATE_CLASS("ClassCastException/NoSuchMethodError (jar hell)"),
    NAMESPACE_MIX("ClassCastException (javax vs jakarta)");

    public final String symptom;

    Kind(String symptom) {
        this.symptom = symptom;
    }
}
