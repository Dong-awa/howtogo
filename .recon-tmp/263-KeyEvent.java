package net.minecraft.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public record KeyEvent(@InputConstants.Value int key, int keycode, @InputWithModifiers.Modifiers int modifiers) implements InputWithModifiers {
    @Override
    public int input() {
        return this.key;
    }

    @Override
    public int shortcutKey() {
        return this.keycode;
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    @org.intellij.lang.annotations.MagicConstant(intValues = {InputConstants.PRESS, InputConstants.RELEASE, InputConstants.REPEAT})
    public @interface Action {
    }
}

