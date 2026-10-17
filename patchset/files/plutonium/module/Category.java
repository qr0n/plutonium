package plutonium.module;

public enum Category {
    MOVEMENT("Movement"),
    VISUAL("Visual"),
    COMBAT("Combat"),
    PLAYER("Player"),
    MISC("Misc");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
