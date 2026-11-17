package net.aspir3.betterbattle.fabric.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.aspir3.betterbattle.fabric.NoTextures;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/** The "no textures" challenge: block textures turn plain white as they load. */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {
    @Inject(method = "<init>(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/metadata/animation/FrameSize;Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At("RETURN"))
    private void betterbattle$whiten(Identifier name, FrameSize size, NativeImage image, CallbackInfo ci) {
        NoTextures.whiten(name, image);
    }

    @Inject(method = "<init>(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/resources/metadata/animation/FrameSize;Lcom/mojang/blaze3d/platform/NativeImage;Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)V", at = @At("RETURN"))
    private void betterbattle$whiten(Identifier name, FrameSize size, NativeImage image, Optional<AnimationMetadataSection> animation,
                                     List<MetadataSectionType.WithValue<?>> extra, Optional<TextureMetadataSection> texture, CallbackInfo ci) {
        NoTextures.whiten(name, image);
    }
}
