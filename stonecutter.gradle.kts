plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11" /* [SC] DO NOT EDIT */

stonecutter {
    tasks {
        order("publishModrinth")
        order("publishCurseforge")
    }
}

stonecutter parameters {
    replacements {
        string(eval(current.version, "= 1.8.9")) {
            replace(
                "com.mojang.blaze3d.platform.InputConstants",
                "org.polyfrost.oneconfig.internal.legacy.InputConstants"
            )
        }
    }
}
