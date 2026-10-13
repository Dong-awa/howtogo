// The project controller: settings shared by every node.
//
// `stonecutter active` is the node you are editing right now. src/ on disk always holds the
// state of that node, and switching it rewrites src/ in place -- the per-version branches are
// toggled by commenting and uncommenting the `//?` markers, which is why the file you read is
// plain Java and Stonecutter's own view lives in those comments.
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3-neoforge"

stonecutter parameters {
    // `//? if neoforge {` -- available in conditions, so loader-specific code can be written
    // next to version-specific code. The fabric constant arrives with the fabric nodes.
    constants["neoforge"] = true

    // Swaps replace a token in the source with a string computed here. mod_version is read by
    // HowToGo.MOD_VERSION via a literal that this keeps in step with gradle.properties.
    swaps["mod_version"] = "\"${properties.get<String>("mod_version")}\";"

    // Replacements rewrite the shared source per version, so a rename that Minecraft made
    // once does not have to be written by hand in every branch of an `if`.
    //
    // Deliberately EMPTY for now. The source on disk is the 26.3 state, which is already
    // written against the modern names; each one is added here as the older nodes are ported,
    // so that a replacement is never declared before the version it applies to actually has
    // the new name.
    replacements {
    }
}
