// The project controller: settings shared by every node.
//
// `stonecutter active` is the node you are editing right now. src/ on disk always holds the
// state of that node, and switching it rewrites src/ in place -- the per-version branches are
// toggled by commenting and uncommenting the `//?` markers, which is why the file you read is
// plain Java and Stonecutter's own view lives in those comments.
//
// The active node is the OLDEST supported version, not the newest. This mod's own development
// happens on 1.21.1, and targets older than that (1.16, 1.18) are plausible later; anchoring on
// the old end means such a port lands as one more node, rather than as a third historical state
// the shared source would have to be rewritten into.
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    // `//? if neoforge {` -- available in conditions, so loader-specific code can be written
    // next to version-specific code. The fabric constant arrives with the fabric nodes.
    constants["neoforge"] = true

    // Swaps replace a token in the source with a string computed here. mod_version is read by
    // HowToGo.MOD_VERSION via a literal that this keeps in step with gradle.properties.
    swaps["mod_version"] = "\"${properties.get<String>("mod_version")}\";"

    // Replacements rewrite the shared source per version, so a rename a release made once does
    // not have to be written by hand at every site.
    //
    // DIRECTION, and why every rule here must be the four-argument regex form. The shared source
    // is written in the OLDEST supported version's API, so a rule is needed when a NEWER release
    // renamed something. Stonecutter's two-argument `replace(a, b)` is BIDIRECTIONAL: it records
    // one spelling plus an alias and applies the pair in whichever direction the node being
    // generated needs. A rule meant to give 26.x a new name therefore also runs, backwards, on
    // 1.21.x -- and written with the arguments the other way round it rewrites the nodes that
    // were already correct. An earlier attempt did exactly that and broke three building nodes.
    //
    // The regex form takes the reverse pattern explicitly, so pinning it to a token that appears
    // nowhere in the source pins the rule to one direction. That is the only safe way to state a
    // rename where one spelling is a prefix of the other, as GuiGraphics is of GuiGraphicsExtractor.
    //
    // Add rules here as each newer node is ported, not in advance: a rule declared before the
    // version that needs it exists is a rule nothing tests.
    replacements {
    }
}
