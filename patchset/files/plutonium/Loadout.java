package plutonium;

/**
 * Compile-time gate for the modules that are only for servers where that sort
 * of thing is the
 * house style.
 *
 * {@link #UNSAFE} is a constant expression, so this is conditional compilation
 * in the sense the
 * language spec means it: with the flag false, javac drops the guarded
 * registrations out of
 * ModuleManager entirely. The built client does not merely hide those modules —
 * its bytecode
 * has no reference to them. They cannot appear in the ClickGUI, cannot be
 * bound, cannot be
 * toggled by anything, and are not written to the config, so a stale
 * {@code enabled: true} left
 * over from a previous session has nothing to switch back on.
 *
 * A runtime flag would have been friendlier to flip, and weaker in exactly the
 * way that matters:
 * a file read at startup is still a code path that can register them by
 * accident. This one is
 * decided before the client exists.
 *
 * To arm them: set UNSAFE to true, rebuild, play somewhere it is welcome. To
 * take the costume
 * off again: set it back to false and rebuild. Flipping it back to false also
 * means the next
 * config save drops their saved settings, so re-arming later starts from
 * defaults rather than
 * from however they were left mid-fight.
 */
public final class Loadout {

    /**
     * Arm the gated modules. Keep false for anywhere with an anticheat, which is to
     * say: keep false.
     */
    public static final boolean UNSAFE = false;

    private Loadout() {
    }
}
