package ch.ace.llmrouter.model;

/** Routing targets. The model behind each target is configured in router.models.*. */
public enum Target {
    LOCAL_SIMPLE("local-simple", false),
    LOCAL_MEDIUM("local-medium", false),
    LOCAL_COMPLEX("local-complex", false),
    CLOUD("cloud", true);

    private final String configKey;
    private final boolean cloud;

    Target(String configKey, boolean cloud) {
        this.configKey = configKey;
        this.cloud = cloud;
    }

    public String configKey() {
        return configKey;
    }

    public boolean isCloud() {
        return cloud;
    }

    public static Target localFor(Complexity complexity) {
        return switch (complexity) {
            case einfach -> LOCAL_SIMPLE;
            case mittel -> LOCAL_MEDIUM;
            case anspruchsvoll -> LOCAL_COMPLEX;
        };
    }
}
