package com.megagraph.shop;
public final class TestImages {
 private TestImages() {}
 public static byte[] png() { return png(0); }
 public static byte[] png(int rgb) {
  try(var bytes=new java.io.ByteArrayOutputStream()) {
   var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
   image.setRGB(0,0,rgb);
   javax.imageio.ImageIO.write(image,"png",bytes);
   return bytes.toByteArray();
  } catch(java.io.IOException e){throw new IllegalStateException(e);}
 }
}
