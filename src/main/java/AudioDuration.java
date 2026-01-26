import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioDuration{
    public static Long getDurationSeconds(String audioPath) {
        try {
            File audioFile = new File(audioPath);
            System.out.println("尝试读取音频文件: " + audioPath);
            System.out.println("文件存在: " + audioFile.exists());
            System.out.println("文件路径: " + audioFile.getAbsolutePath());
            System.out.println("文件格式: " + audioPath.substring(audioPath.lastIndexOf('.') + 1));
            
            // 首先尝试使用 jaudiotagger 库读取
            try {
                return readDurationWithJaudiotagger(audioFile);
            } catch (Exception e) {
                System.err.println("jaudiotagger 读取失败，尝试使用备用方法: " + e.getMessage());
                // 如果失败，尝试使用备用方法
                if (audioPath.toLowerCase().endsWith(".m4a")) {
                    return readM4aDuration(audioFile);
                }
                throw e;
            }
        } catch (Exception e) {
            System.err.println("读取音频文件失败: " + e.getMessage());
            e.printStackTrace();
            return 0L;
        }
    }
    
    private static Long readDurationWithJaudiotagger(File audioFile) throws Exception {
        org.jaudiotagger.audio.AudioFile f = org.jaudiotagger.audio.AudioFileIO.read(audioFile);
        org.jaudiotagger.audio.AudioHeader header = f.getAudioHeader();
        long duration = (long) header.getTrackLength();
        System.out.println("使用 jaudiotagger 成功读取音频时长: " + duration + "秒");
        return duration;
    }
    
    private static Long readM4aDuration(File audioFile) throws Exception {
        // 简单的 M4A 文件时长读取方法
        // 注意：这只是一个基本实现，可能无法处理所有 M4A 文件
        try (RandomAccessFile raf = new RandomAccessFile(audioFile, "r")) {
            byte[] buffer = new byte[8];
            
            // 搜索 moov 原子
            while (raf.getFilePointer() < raf.length() - 8) {
                raf.readFully(buffer, 0, 4);
                int atomSize = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                
                raf.readFully(buffer, 0, 4);
                String atomType = new String(buffer, 0, 4, "UTF-8");
                
                if ("moov".equals(atomType)) {
                    // 找到 moov 原子，搜索 mvhd 原子
                    long moovEnd = raf.getFilePointer() + atomSize - 8;
                    while (raf.getFilePointer() < moovEnd - 8) {
                        raf.readFully(buffer, 0, 4);
                        int mvhdSize = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                        
                        raf.readFully(buffer, 0, 4);
                        String mvhdType = new String(buffer, 0, 4, "UTF-8");
                        
                        if ("mvhd".equals(mvhdType)) {
                            // 找到 mvhd 原子，读取时长
                            raf.skipBytes(12); // 跳过版本、标志和创建时间
                            raf.skipBytes(4); // 跳过修改时间
                            
                            raf.readFully(buffer, 0, 4);
                            int timeScale = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                            
                            raf.readFully(buffer, 0, 4);
                            int duration = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                            
                            if (timeScale > 0) {
                                long seconds = duration / timeScale;
                                System.out.println("使用备用方法成功读取 M4A 时长: " + seconds + "秒");
                                return seconds;
                            }
                            break;
                        } else {
                            // 跳过其他原子
                            raf.skipBytes(mvhdSize - 8);
                        }
                    }
                    break;
                } else {
                    // 跳过其他原子
                    raf.skipBytes(atomSize - 8);
                }
            }
        }
        System.err.println("备用方法读取 M4A 时长失败");
        return 0L;
    }

}
