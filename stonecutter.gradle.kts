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
    // Loader constants are NOT set here. This block is shared by every node, so a constant set
    // here would be true on all of them -- and `//? if neoforge` being true on a fabric node
    // would compile the NeoForge half of every loader condition in the tree. Each loader's build
    // script declares its own instead, which is the only place that knows which node it is
    // building: see the `stonecutter.const(...)` call at the top of build.<loader>.gradle.kts.

    // Swaps replace a token in the source with a string computed here. mod_version is read by
    // HowToGo.MOD_VERSION via a literal that this keeps in step with gradle.properties.
    swaps["mod_version"] = "\"${properties.get("mod_version")}\";"

    // Replacements rewrite the shared source per version, so a rename a release made once does
    // not have to be written by hand at every site.
    //
    // DIRECTION. The shared source is written in the OLDEST supported version's API, so a rule is
    // needed when a NEWER release renamed something. Stonecutter's bare `replace(a, b)` is
    // BIDIRECTIONAL: it records one spelling plus an alias and applies the pair in whichever
    // direction the node being generated needs. A rule meant to give 26.x a new name therefore also
    // runs, backwards, on 1.21.x -- and written with the arguments the other way round it rewrites
    // the nodes that were already correct. An earlier attempt did exactly that and broke three
    // building nodes.
    //
    // So every rule here states both directions: `replace(from, to)` for the forward one and
    // `reverse("NO_REVERSE", "NO_REVERSE")` to pin the backward one to a token that appears nowhere
    // in the source. (On Stonecutter 0.9 this was one four-argument call; 0.7 split it into two
    // methods with the same meaning.) That is the only safe way to state a rename where one spelling
    // is a prefix of the other, as GuiGraphics is of GuiGraphicsExtractor.
    //
    // ORDER matters here, which it did not before: 0.7 applies these in sequence, so a rule's
    // pattern must be written against what the earlier rules will have produced, not against the
    // source as written. The two screen-signature rules below are the ones where that bites.
    //
    // Add rules here as each newer node is ported, not in advance: a rule declared before the
    // version that needs it exists is a rule nothing tests.
    replacements {
        // Fabric API renamed a family of classes in 26.1. These are spellings, so they are rules
        // rather than conditions -- and each rule block takes exactly one replace, which is why
        // there is a block per rename instead of one block with several.
        //
        // Authority: docs/fabric-api-26.1-porting.md, the 26.1 porting guide.
        regex(eval(current.version, ">=26.1")) {
            replace("net.fabricmc.fabric.api.client.keybinding.v1", "net.fabricmc.fabric.api.client.keymapping.v1")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("KeyBindingHelper.registerKeyBinding", "KeyMappingHelper.registerKeyMapping")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("KeyBindingHelper", "KeyMappingHelper")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("net.fabricmc.fabric.api.itemgroup.v1", "net.fabricmc.fabric.api.creativetab.v1")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("net.fabricmc.fabric.api.client.command.v2.ClientCommandManager", "net.fabricmc.fabric.api.client.command.v2.ClientCommands")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("ClientCommandManager.", "ClientCommands.")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // @Mod gained its dist attribute at 1.20.5, which is what made "this mod is client-only" a
        // declaration rather than a convention. Below that the annotation takes the id alone.
        //
        // A rule rather than a condition because it is purely a spelling: same annotation, same id,
        // and the restriction it expressed did not exist to lose. The alternative was a version
        // condition on the class declaration, which sits inside the loader fence in HowToGo and
        // HowToGoServer and would nest.
        regex(eval(current.version, "<1.20.5")) {
            // One replace per block -- the second call is rejected with "the value for property
            // 'fromPattern' cannot be changed any further" -- so the two dist values alternate.
            replace("@Mod\\(value = HowToGo\\.MODID, dist = Dist\\.(?:CLIENT|DEDICATED_SERVER)\\)",
                    "@Mod(HowToGo.MODID)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // 1.21 made ResourceLocation's constructor private and added fromNamespaceAndPath. The one
        // place this mod constructs one directly is the Fabric item registration -- NeoForge registers
        // through registerItem, which builds the id itself -- and that call site is inside a loader
        // condition, so the difference has to be a rule rather than a condition there.
        //
        // It is placed BEFORE the ResourceLocation-to-Identifier rename below, because replacements
        // apply in sequence: on 1.21.9 and later the rename would otherwise have already rewritten
        // the type name and this pattern would match nothing. Both spellings are accepted here so
        // that the two rules compose whichever order the versions put them in.
        regex(eval(current.version, ">=1.21")) {
            replace("new (ResourceLocation|Identifier)\\(([^,]+), ([^)]+)\\)",
                    "$1.fromNamespaceAndPath($2, $3)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
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
        regex(eval(current.version, ">=1.21.9")) {
            replace("net\\.minecraft\\.resources\\.ResourceLocation",
                    "net.minecraft.resources.Identifier")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=1.21.9")) {
            replace("ResourceLocation", "Identifier")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The accessor that hands out that type was renamed with it: location() became identifier().
        // Matched with its receiver and arguments so it cannot touch an unrelated location() on some
        // other class.
        regex(eval(current.version, ">=1.21.9")) {
            replace("\\.location\\(\\)", ".identifier()")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // 1.21.9 moved the static render types onto a class of their own. RenderType.create is gone
        // in favour of RenderTypes, and the ready-made ones -- debugQuads and friends -- moved with
        // it, so the import has to move too.
        regex(eval(current.version, ">=1.21.9")) {
            replace("net\\.minecraft\\.client\\.renderer\\.RenderType",
                    "net.minecraft.client.renderer.rendertype.RenderTypes")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The ready-made render types moved with it onto that class: RenderType.debugQuads() is
        // RenderTypes.debugQuads() now. Matched as a qualified call so the name alone is not
        // rewritten inside MultiTextureRenderTypeRendererProvider and the renderer signatures, which
        // keep their own names.
        regex(eval(current.version, ">=1.21.9")) {
            replace("RenderType\\.", "RenderTypes.")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The keyboard-modifier helpers left Screen for Minecraft, where the window and its input
        // state already live. Same three questions about the current keystate, asked of the game.
        regex(eval(current.version, ">=1.21.9")) {
            replace("Screen\\.has(Control|Shift|Alt)Down\\(\\)",
                    "Minecraft.getInstance().has$1Down()")
            reverse("NO_REVERSE", "NO_REVERSE")
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
        // follow it.
        //
        // The exclusion is done by NOT going through this rule at all for that call. A rule matches
        // text and cannot see a receiver's declared type, and the type appears on the method's
        // signature line while the call sits several lines below it -- so a single-line lookahead
        // cannot connect the two, which two attempts at this established. MapLabels.java writes that
        // one call through a placeholder instead, and the placeholder is resolved by the rule below.
        regex(eval(current.version, ">=26.1")) {
            replace("\\.drawString\\(", ".text(")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("\\.drawCenteredString\\(", ".centeredText(")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // Puts Xaero's method name back. A placeholder rather than a second rename rule, because
        // replacements are applied as one set and no rule sees another's output -- a rule matching
        // the renamed text never fires. HTG_MAP_TEXT appears nowhere else in the tree.
        //
        // Gated at the anchor rather than at 26.1: every node compiles MapLabels, so every node has
        // to resolve the token. Gating it at 26.1 left the older nodes with the literal placeholder in
        // their source, which is what the previous build said.
        regex(eval(current.version, ">=1.21.1")) {
            replace("HTG_MAP_TEXT", "drawString")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The type itself moved too, and with the same one-way treatment: every file that names it
        // names it in a parameter list or a local, and there is no version on which both names exist.
        // The newer name is longer, so the older rule must not run after it -- hence the separate
        // package-sensitive rule first, then the bare name, both one-way.
        regex(eval(current.version, ">=26.1")) {
            replace("net\\.minecraft\\.client\\.gui\\.GuiGraphics",
                    "net.minecraft.client.gui.GuiGraphicsExtractor")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("\\bGuiGraphics\\b", "GuiGraphicsExtractor")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The element-state interface moved from the GUI package to the renderer's own state package,
        // which is where the renderer that plays it back lives.
        regex(eval(current.version, ">=26.1")) {
            replace("net\\.minecraft\\.client\\.gui\\.render\\.state\\.GuiElementRenderState",
                    "net.minecraft.client.renderer.state.gui.GuiElementRenderState")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // Xaero's element renderer stopped taking a vanilla buffer source at 26.1: the mod's
        // MultiBufferSource.BufferSource parameters are XaeroBufferProvider there. Its own interface
        // moved with it, and the vanilla class the parameters named is gone.
        // Xaero's element renderer stopped taking a vanilla buffer source at 26.2: the mod's
        // MultiBufferSource.BufferSource parameters are XaeroBufferProvider from there on. Bounded
        // there and not at 26.1, where Xaero still used the vanilla type -- the same lesson as the
        // render pipeline below, and one I got wrong twice.
        regex(eval(current.version, ">=26.2")) {
            replace("net\\.minecraft\\.client\\.renderer\\.MultiBufferSource",
                    "xaero.lib.client.graphics.XaeroBufferProvider")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.2")) {
            replace("MultiBufferSource\\.BufferSource", "XaeroBufferProvider")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The render pipeline left the blaze3d package for the new renderpearl API, and the builder
        // chain is otherwise unchanged -- same snippets, same withLocation/withCull/build.
        //
        // Bounded at 26.3 and not at 26.1, which is where I first put it: 26.2 still has the old
        // package. The 26.x line is not uniform across its own minor versions, so each of these
        // boundaries is worth checking against the node's own jar rather than assumed from the
        // major version.
        // 1.21.6 replaced ClickEvent(Action, String) with one subclass per action. The mod only ever
        // makes one: a clickable link in the chat message a command sends back. OpenUrl carries the
        // same link but holds a URI, so the string is parsed at the construction site.
        //
        // A rule rather than a condition at that site because the command tree is written once per
        // loader and a version condition inside a loader condition would nest -- Stonecutter's scopes
        // do not allow it. A version-gated replacement is exactly the tool for a difference that is
        // purely a spelling, which this one is: same event, same link, same click.
        regex(eval(current.version, ">=1.21.6")) {
            replace("new ClickEvent\\(ClickEvent\\.Action\\.OPEN_URL, ([A-Za-z_][A-Za-z0-9_]*)\\)",
                    "new ClickEvent.OpenUrl(java.net.URI.create($1))")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.3")) {
            replace("com\\.mojang\\.blaze3d\\.pipeline\\.RenderPipeline",
                    "com.mojang.renderpearl.api.pipeline.RenderPipeline")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // A screen states rather than renders from 26.1: the entry point is extractRenderState and the
        // background hook is extractBackground. Matched on the whole signature including the parameter
        // types, which is what tells these two apart from the many other render* methods here.
        //
        // These two patterns name GuiGraphicsExtractor, the POST-rename spelling, and that is load
        // bearing. Stonecutter 0.7 applies replacements in sequence -- each rule sees the previous
        // rule's output -- so by the time these run, the GuiGraphics rule above has already rewritten
        // the parameter type and a pattern looking for `GuiGraphics` matches nothing. 0.9 applied them
        // in a single pass over the original text, where the opposite was true. This is the one place
        // the two behaviours differ in this file, and it cost a build cycle to find.
        regex(eval(current.version, ">=26.1")) {
            replace("void renderBackground\\(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick\\)",
                    "void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("void render\\(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick\\)",
                    "void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("super\\.render\\(graphics, mouseX, mouseY, partialTick\\)",
                    "super.extractRenderState(graphics, mouseX, mouseY, partialTick)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        regex(eval(current.version, ">=26.1")) {
            replace("parent\\.render\\(graphics, mouseX, mouseY, partialTick\\)",
                    "parent.extractRenderState(graphics, mouseX, mouseY, partialTick)")
            reverse("NO_REVERSE", "NO_REVERSE")
        }
        // The map's viewport question is NOT a rule here, and neither is its text call. Xaero's own
        // MapElementGraphics reports no size of its own and keeps drawString -- it is not the vanilla
        // type and did not follow it -- so the blanket .drawString( rule above must not reach it.
        // Both live behind conditions in RoadElementRenderer instead. The general lesson: a rename
        // rule cannot tell one receiver's method from another's, so it is only safe when every
        // receiver in the tree agrees. That is worth checking before writing one.
    }
}
