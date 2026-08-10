package dev.omnist.document;

/**
 * A target is either a value (Scalar or null) or a Node (omnist-spec §2.2).
 */
public sealed interface Target permits Scalar, Node, Target.NullTarget {

    /**
     * Singleton NullTarget instance.
     */
    NullTarget NULL = NullTarget.INSTANCE;

    /**
     * Represents the null value in the Document model (omnist-spec §2.2.1).
     */
    record NullTarget() implements Target {
        public static final NullTarget INSTANCE = new NullTarget();
    }
}
