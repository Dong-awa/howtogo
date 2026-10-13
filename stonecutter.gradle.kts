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

    // Replacements rewrite the shared source per version, so a rename a release made once does
    // not have to be written by hand at every site. Each rule is declared only for the nodes
    // that need it; the shared source is written in the NEWEST spelling and an older node gets
    // the older name substituted back in.
    //
    // Every rule here is the four-argument `replace(pattern, value, reversePattern, value)`, with
    // the reverse pinned to NO_REVERSE -- a token that appears nowhere in the source. That shape
    // is deliberate, and the two facts behind it cost most of a day to find:
    //
    // 1. A two-argument `string` replacement does NOT mean "rewrite a to b on the nodes this
    //    block is true for". It records one canonical spelling plus an alias and lets Stonecutter
    //    apply the pair in whichever direction the node being generated needs, so a rule written
    //    for the old nodes is live, REVERSED, on the new ones. With the arguments the wrong way
    //    round the rule does not merely fail to help: it rewrites the new nodes' correct code
    //    back into the old spelling. An earlier attempt built all three 26.x nodes that were
    //    green with a rule meant for 1.21.x, and broke exactly those three.
    // 2. Because both directions are always live, one spelling must not be a substring of the
    //    other. GuiGraphics is a prefix of GuiGraphicsExtractor, so that rename cannot be stated
    //    as a `string` pair at all: the reverse direction would fire on the 26.x nodes' genuine
    //    GuiGraphics and corrupt them.
    //
    // Declaring the reverse explicitly solves both. The forward pattern is a plain regex whose
    // match is the whole old spelling, and the reverse can never match, so the rule only ever
    // runs one way. The condition still selects the nodes: it is true for the older ones, which
    // get the old API, and false for the 26.x ones, where the inert reverse leaves them alone.
    replacements {
        // Xaero's element renderer swapped its live-buffer parameter when the third-tier
        // rendering API arrived. 26.1 hands the renderer a vanilla BufferSource; 26.2 moved the
        // same role into XaeroLib's own provider. The call shape is identical --
        // getBuffer(RenderType) returns a VertexConsumer either way -- so only the type name
        // changes, and the import changes with it because the two live in different packages.
        regex(current.parsed < "26.2") {
            replace("xaero\\.lib\\.client\\.graphics\\.XaeroBufferProvider",
                    "net.minecraft.client.renderer.MultiBufferSource",
                    "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed < "26.2") {
            replace("XaeroBufferProvider", "MultiBufferSource.BufferSource",
                    "NO_REVERSE", "NO_REVERSE")
        }

        // 26.1 rebuilt GUI drawing around an extraction pass: what used to be GuiGraphics -- the
        // object a screen or HUD handler draws through -- is now GuiGraphicsExtractor. The object
        // plays the same role and no call site changes shape, so this is a pure rename.
        regex(current.parsed < "26.1") {
            replace("GuiGraphicsExtractor", "GuiGraphics", "NO_REVERSE", "NO_REVERSE")
        }

        // The same wave shortened the text call on that object: GuiGraphics.drawString became
        // GuiGraphicsExtractor.text, overload for overload (String, Component and
        // FormattedCharSequence, each with and without the drop-shadow flag). The source is
        // written in the new spelling, so the pattern is the new one and an older node gets the
        // old name back. The receiver is part of the pattern because Xaero's buffer source keeps
        // its own drawString and the map renderers legitimately call that one, so only the GUI
        // drawing object the panels are handed is rewritten.
        regex(current.parsed < "26.1") {
            replace("graphics\\.text\\(", "graphics.drawString(", "NO_REVERSE", "NO_REVERSE")
        }

        // The extraction pass renamed the state hook alongside the context: what a screen now
        // overrides as extractRenderState is what it used to override as render, and
        // extractBackground was renderBackground. Same arguments, same contract -- the method is
        // simply called by a different vanilla pass -- so the two are one substitution each.
        // Both patterns carry the whole method name, so RenderPipeline.render and the element
        // renderer's preRender/postRender -- which keep their names -- are never touched.
        regex(current.parsed < "26.1") {
            replace("extractRenderState\\(", "render(", "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed < "26.1") {
            replace("extractBackground\\(", "renderBackground(", "NO_REVERSE", "NO_REVERSE")
        }

        // The GUI render state moved package in the same release, and with it the bounds() method
        // the batch reports its extent through. The package prefix is what changed, so the rule
        // matches that rather than the simple name.
        regex(current.parsed < "26.1") {
            replace("net\\.minecraft\\.client\\.renderer\\.state\\.gui\\.GuiElementRenderState",
                    "net.minecraft.client.gui.render.state.GuiElementRenderState",
                    "NO_REVERSE", "NO_REVERSE")
        }
    }
}