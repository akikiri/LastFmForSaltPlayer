import java.io.File;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;

public class AudioDuration{
    public static Long getDurationSeconds(String audioPath) {
        try {
            File audioFile = new File(audioPath);
            AudioFile f = AudioFileIO.read(audioFile);
            AudioHeader header = f.getAudioHeader();
            return (long) header.getTrackLength();
        } catch (Exception e) {
            System.err.println("读取音频文件失败: " + e.getMessage());
            return 0L;
        }
    }

}
