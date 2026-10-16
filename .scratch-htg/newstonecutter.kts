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
    //
    // Two properties of this mechanism decide how every rule below is written, and both cost
    // real time to find:
    //
    // 1. `replace(a, b)` records ONE canonical spelling plus an alias, not a one-way rewrite.
    //    The first argument is the spelling the shared source is written in; the second is the
    //    older alternative. Stonecutter then applies the pair in whichever direction the node
    //    needs, so a rule declared for the old nodes is also live, REVERSED, on the new ones.
    //    Writing the arguments the other way round does not merely fail to help -- it rewrites
    //    the new nodes' correct code back into the old spelling and breaks nodes that were
    //    building green.
    //
    // 2. Because the pair is matched textually and in both directions, ONE SPELLING MUST NOT BE
    //    A SUBSTRING OF THE OTHER. `GuiGraphics` is a prefix of `GuiGraphicsExtractor`, so that
    //    rename cannot be written as a bare name pair: the reverse direction would fire on the
    //    26.x nodes' genuine `GuiGraphics` and rewrite it to `GuiGraphicsExtractor`. The
    //    declaration and use sites are therefore matched with a trailing character carried in
    //    the pattern, which keeps the two spellings disjoint and pins each rule to a real site.
    replacements {
        // Xaero's element renderer swapped its live-buffer parameter when the third-tier
        // rendering API arrived. 26.1 hands the renderer a vanilla BufferSource; 26.2 moved the
        // same role into XaeroLib's own provider. The call shape is identical --
        // getBuffer(RenderType) returns a VertexConsumer either way -- so only the type name
        // changes. The import is rewritten first and in full, because the two types live in
        // different packages: a bare name replacement alone would leave a nonsense import.
        string(current.parsed < "26.2") {
            replace("import net.minecraft.client.renderer.MultiBufferSource;",
                    "import xaero.lib.client.graphics.XaeroBufferProvider;")
            replace("MultiBufferSource.BufferSource", "XaeroBufferProvider")
        }

        // 26.1 rebuilt GUI drawing around an extraction pass: what used to be GuiGraphics -- the
        // object a screen or HUD handler draws through -- is now GuiGraphicsExtractor. The object
        // plays the same role and no call site changes shape, so this is a pure rename.
        //
        // The import is rewritten first and in full so that an old node imports the class it
        // actually has. The declaration and parameter uses go through the suffixed patterns
        // below rather than one bare name pair, for reason 2 above: a plain
        // replace("GuiGraphicsExtractor", "GuiGraphics") here would rewrite the 26.x nodes' own
        // `GuiGraphics` into `GuiGraphicsExtractor` and break all three of them. Between them the
        // three suffixes cover every way the type is written -- as a declared or parameter type
        // (space), and as the closing token of a cast, generic argument or return type (`;`,
        // `)`), which is every occurrence in this source.
        string(current.parsed < "26.1") {
            replace("import net.minecraft.client.gui.GuiGraphics;",
                    "import net.minecraft.client.gui.GuiGraphicsExtractor;")
            replace("GuiGraphicsExtractor ", "GuiGraphics ")
            replace("GuiGraphicsExtractor;", "GuiGraphics;")
            replace("GuiGraphicsExtractor)", "GuiGraphics)")
        }

        // The same wave shortened the text call on that object: GuiGraphics.drawString became
        // GuiGraphicsExtractor.text, overload for overload (String, Component and
        // FormattedCharSequence, each with and without the drop-shadow flag). The receiver is
        // part of the pattern because Xaero's buffer source keeps its own drawString and the map
        // renderers legitimately call that one, so only the GUI drawing object is rewritten.
        string(current.parsed < "26.1") {
            replace("graphics.drawString(", "graphics.text(")
        }

        // The extraction pass renamed the state hook alongside the context: what a screen now
        // overrides as extractRenderState is what it used to override as render, and
        // extractBackground was renderBackground. Same arguments, same contract -- the method is
        // simply called by a different vanilla pass -- so the two are one substitution each.
        // There are exactly two shapes, the declaration and the super/parent call, and both
        // patterns carry the whole method name and the start of its parameter list, so
        // RenderPipeline.render and the element renderer's preRender/postRender -- which keep
        // their names -- are never touched. A bare replace("render(", ...) would hit those; that
        // is why it is not used.
        string(current.parsed < "26.1") {
            replace("void render(GuiGraphics", "void extractRenderState(GuiGraphics")
            replace("void renderBackground(GuiGraphics", "void extractBackground(GuiGraphics")
            replace(".render(GuiGraphics", ".extractRenderState(GuiGraphics")
            replace(".renderBackground(GuiGraphics", ".extractBackground(GuiGraphics")
        }

        // The GUI render state moved package in the same release, and with it the bounds() method
        // the batch reports its extent through. Rewritten as a whole import for the usual reason:
        // the bare name alone would leave an import of a package the node does not have.
        string(current.parsed < "26.1") {
            replace("import net.minecraft.client.gui.render.state.GuiElementRenderState;",
                    "import net.minecraft.client.renderer.state.gui.GuiElementRenderState;")
        }
    }
}