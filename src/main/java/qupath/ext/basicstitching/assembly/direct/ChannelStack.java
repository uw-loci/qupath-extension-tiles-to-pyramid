package qupath.ext.basicstitching.assembly.direct;

import java.awt.image.BufferedImage;
import java.util.List;
import qupath.lib.images.servers.ImageServer;

/**
 * An image whose channels are the channels of other images, side by side and unchanged.
 *
 * <p>{@link DirectTiffOutputWriter} writes each channel plane of such an image from the image that
 * holds that channel, so writing one channel does not read the others.
 */
public interface ChannelStack {

    /**
     * @return the images whose channels, in this order, are this image's channels pixel for pixel;
     *     all the same size, pixel type and number of z-slices and time points as this image
     */
    List<ImageServer<BufferedImage>> getChannelSources();
}
