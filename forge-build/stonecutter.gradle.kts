// The controller script for the Forge build.
//
// The `//?` replacement rules live in the main tree's controller and are version rules rather
// than loader rules, so a node here needs exactly the same processing as its main-tree
// counterpart. They are applied rather than copied, so the two builds cannot drift.

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.20.1-forge"

// The main controller also declares its own active node and its loader constants, so only the
// replacements block is wanted from it. Splitting that out is the next step; until then this
// build has no replacement rules and a node generated from it would differ from the main tree.
// apply(from = "../stonecutter-replacements.gradle.kts")