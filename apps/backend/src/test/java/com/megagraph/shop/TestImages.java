package com.megagraph.shop;
public final class TestImages {
 private TestImages() {}
 public static byte[] png() {
  try(var bytes=new java.io.ByteArrayOutputStream()) {
   javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
   return bytes.toByteArray();
  } catch(java.io.IOException e){throw new IllegalStateException(e);}
 }
}
