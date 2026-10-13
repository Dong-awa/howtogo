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
        // 1.21.9 renamed ResourceLocation to Identifier and rehomed it, and renamed the accessor
        // that hands one out from location() to identifier().
        //
        // Each rename is its own regex block, with the reverse pinned to NO_REVERSE, for the reasons
        // the header gives: the two-argument form is bidirectional and would rewrite the older nodes
        // backwards. Two rules for the type because the package moved as well -- a bare-name rule
        // alone would leave an import of a package the newer node does not have.
        //
        // Add rules here only when the shared source genuinely cannot express both shapes; a rename
        // that a condition at the call site can carry is clearer left as a condition.
        regex(current.parsed >= "1.21.9") {
            replace("net\\.minecraft\\.resources\\.ResourceLocation",
                    "net.minecraft.resources.Identifier", "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed >= "1.21.9") {
            replace("ResourceLocation", "Identifier", "NO_REVERSE", "NO_REVERSE")
        }
        // The accessor that hands out that type was renamed with it: location() became identifier().
        // Matched with its receiver and arguments so it cannot touch an unrelated location() on some
        // other class.
        regex(current.parsed >= "1.21.9") {
            replace("\\.location\\(\\)", ".identifier()", "NO_REVERSE", "NO_REVERSE")
        }
        // 1.21.9 moved the static render types onto a class of their own. RenderType.create is gone
        // in favour of RenderTypes, and the ready-made ones -- debugQuads and friends -- moved with
        // it, so the import has to move too.
        regex(current.parsed >= "1.21.9") {
            replace("net\\.minecraft\\.client\\.renderer\\.RenderType",
                    "net.minecraft.client.renderer.rendertype.RenderTypes", "NO_REVERSE", "NO_REVERSE")
        }
        // The ready-made render types moved with it onto that class: RenderType.debugQuads() is
        // RenderTypes.debugQuads() now. Matched as a qualified call so the name alone is not
        // rewritten inside MultiTextureRenderTypeRendererProvider and the renderer signatures, which
        // keep their own names.
        regex(current.parsed >= "1.21.9") {
            replace("RenderType\\.", "RenderTypes.", "NO_REVERSE", "NO_REVERSE")
        }
        // The keyboard-modifier helpers left Screen for Minecraft, where the window and its input
        // state already live. Same three questions about the current keystate, asked of the game.
        regex(current.parsed >= "1.21.9") {
            replace("Screen\\.has(Control|Shift|Alt)Down\\(\\)",
                    "Minecraft.getInstance().has$1Down()", "NO_REVERSE", "NO_REVERSE")
        }

        // ---------------------------------------------------------------------------------------
        // 26.x, where the GUI's drawing facade was replaced rather than renamed around.
        //
        // GuiGraphics is gone. What a screen is handed now is a GuiGraphicsExtractor, and the verbs
        // moved with it: a string is no longer drawn but extracted, so drawString is text() and
        // drawCenteredString is centeredText(). Sixty-odd call sites across six files, which is why
        // these are replacements and not conditions.
        //
        // One receiver in this tree disagrees and is deliberately excluded: Xaero's own
        // MapElementGraphics kept drawString in 26.3, because it is not the vanilla type and did not
        // follow it. Its calls live in MapLabels.java, a whole-file condition on the era, so this rule
        // never reaches them -- a rule cannot be told to skip one file, but it can be kept away from
        // one by moving the dissenting call somewhere the rule does not look.
        regex(current.parsed >= "26.1") {
            replace("\\.drawString\\(", ".text(", "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed >= "26.1") {
            replace("\\.drawCenteredString\\(", ".centeredText(", "NO_REVERSE", "NO_REVERSE")
        }
        // The type itself moved too, and with the same one-way treatment: every file that names it
        // names it in a parameter list or a local, and there is no version on which both names exist.
        //
        // The newer name is longer, so the older rule must not run after it -- hence the separate
        // package-sensitive rule first, then the bare name, both one-way.
        regex(current.parsed >= "26.1") {
            replace("net\\.minecraft\\.client\\.gui\\.GuiGraphics",
                    "net.minecraft.client.gui.GuiGraphicsExtractor", "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed >= "26.1") {
            replace("\\bGuiGraphics\\b", "GuiGraphicsExtractor", "NO_REVERSE", "NO_REVERSE")
        }
        // The element-state interface moved from the GUI package to the renderer's own state package,
        // which is where the renderer that plays it back lives.
        regex(current.parsed >= "26.1") {
            replace("net\\.minecraft\\.client\\.gui\\.render\\.state\\.GuiElementRenderState",
                    "net.minecraft.client.renderer.state.gui.GuiElementRenderState",
                    "NO_REVERSE", "NO_REVERSE")
        }
        // Xaero's element renderer stopped taking a vanilla buffer source at 26.1: the mod's
        // MultiBufferSource.BufferSource parameters are XaeroBufferProvider there. Its own interface
        // moved with it, and the vanilla class the parameters named is gone.
        regex(current.parsed >= "26.1") {
            replace("net\\.minecraft\\.client\\.renderer\\.MultiBufferSource",
                    "xaero.lib.client.graphics.XaeroBufferProvider", "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed >= "26.1") {
            replace("MultiBufferSource\\.BufferSource", "XaeroBufferProvider", "NO_REVERSE", "NO_REVERSE")
        }
        // The render pipeline left the blaze3d package for the new renderpearl API, and the builder
        // chain is otherwise unchanged -- same snippets, same withLocation/withCull/build.
        regex(current.parsed >= "26.1") {
            replace("com\\.mojang\\.blaze3d\\.pipeline\\.RenderPipeline",
                    "com.mojang.renderpearl.api.pipeline.RenderPipeline", "NO_REVERSE", "NO_REVERSE")
        }
        // A screen no longer renders itself but states what should be rendered, so its entry point was
        // renamed and the two delegated calls in the screens go with it. Anchored on the receiver and
        // the leading parameters so the many other render* methods here are left alone.
        regex(current.parsed >= "26.1") {
            replace("super\\.render\\(graphics, mouseX, mouseY, partialTick\\)",
                    "super.extractRenderState(graphics, mouseX, mouseY, partialTick)",
                    "NO_REVERSE", "NO_REVERSE")
        }
        regex(current.parsed >= "26.1") {
            replace("parent\\.render\\(graphics, mouseX, mouseY, partialTick\\)",
                    "parent.extractRenderState(graphics, mouseX, mouseY, partialTick)",
                    "NO_REVERSE", "NO_REVERSE")
        }
        // The map's viewport question is NOT a rule here, and neither is its text call. Xaero's own
        // MapElementGraphics reports no size of its own and keeps drawString -- it is not the vanilla
        // type and did not follow it -- so the blanket .drawString( rule above must not reach it.
        // Both live behind conditions in RoadElementRenderer instead. The general lesson: a rename
        // rule cannot tell one receiver's method from another's, so it is only safe when every
        // receiver in the tree agrees. That is worth checking before writing one.
    }
}
