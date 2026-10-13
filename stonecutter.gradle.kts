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

    // Replacements rewrite the shared source per version, so a rename that a release made once
    // does not have to be written by hand at every site. They are textual and global within the
    // node they apply to, so each one is declared only where the new name really exists: the
    // shared source is written against the NEWEST spelling, and an older node gets the older
    // name substituted back in.
    replacements {
        // Xaero's element renderer swapped its live-buffer parameter when the third-tier
        // rendering API arrived. 26.1 hands the renderer a vanilla BufferSource; 26.2 moved the
        // same role into XaeroLib's own provider. The call shape is identical --
        // getBuffer(RenderType) returns a VertexConsumer either way -- so only the type name
        // changes. The import is rewritten first and in full, because the two types live in
        // different packages: a bare name replacement alone would leave a nonsense import.
        string(current.parsed < "26.2") {
            replace("import xaero.lib.client.graphics.XaeroBufferProvider;",
                    "import net.minecraft.client.renderer.MultiBufferSource;")
            replace("XaeroBufferProvider", "MultiBufferSource.BufferSource")
        }
    }
}
