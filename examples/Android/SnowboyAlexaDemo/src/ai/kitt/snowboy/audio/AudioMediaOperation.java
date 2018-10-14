package ai.kitt.snowboy.audio;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.security.auth.callback.Callback;

import ai.kitt.snowboy.Constants;

public class AudioMediaOperation {
    public interface OperationCallbacks {
        public void onAudioOperationFinished();
        public void onAudioOperationError(Exception e);
    }

    public static void MergeAudios(String[] selection, String outpath, OperationCallbacks callback)
    {
        int RECORDER_SAMPLERATE = 0;
        try {
            DataOutputStream amplifyOutputStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outpath)));
            DataInputStream[] mergeFilesStream = new DataInputStream[selection.length];
            long[] sizes=new long[selection.length];
            for(int i=0; i<selection.length; i++) {
                File file = new File(selection[i]);
                sizes[i] = (file.length()-44)/2;
            }
            for(int i =0; i<selection.length; i++) {
                mergeFilesStream[i] =new DataInputStream(new BufferedInputStream(new FileInputStream(selection[i])));

                if(i == selection.length-1) {
                    mergeFilesStream[i].skip(24);
                    byte[] sampleRt = new byte[4];
                    mergeFilesStream[i].read(sampleRt);
                    ByteBuffer bbInt = ByteBuffer.wrap(sampleRt).order(ByteOrder.LITTLE_ENDIAN);
                    RECORDER_SAMPLERATE = bbInt.getInt();
                    mergeFilesStream[i].skip(16);
                }
                else {
                    mergeFilesStream[i].skip(44);
                }
            }

            for(int b=0; b<selection.length; b++) {
                for(int i=0; i<(int)sizes[b]; i++) {
                    byte[] dataBytes = new byte[2];
                    try {
                        dataBytes[0] = mergeFilesStream[b].readByte();
                        dataBytes[1] = mergeFilesStream[b].readByte();
                    }
                    catch (EOFException e) {
                        amplifyOutputStream.close();
                    }
                    short dataInShort = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN).getShort();
                    float dataInFloat= (float) dataInShort/37268.0f;

                    short outputSample = (short)(dataInFloat * 37268.0f);
                    byte[] dataFin = new byte[2];
                    dataFin[0] = (byte) (outputSample & 0xff);
                    dataFin[1] = (byte)((outputSample >> 8) & 0xff);
                    amplifyOutputStream.write(dataFin, 0 , 2);

                }
            }
            amplifyOutputStream.close();
            for(int i=0; i<selection.length; i++) {
                mergeFilesStream[i].close();
            }

        } catch (FileNotFoundException e) {
            if (callback != null){
                callback.onAudioOperationError(e);
            }
            e.printStackTrace();
        } catch (IOException e) {
            if (callback != null){
                callback.onAudioOperationError(e);
            }
            e.printStackTrace();
        }
        long size =0;
        try {
            FileInputStream fileSize = new FileInputStream(outpath);
            size = fileSize.getChannel().size();
            fileSize.close();
        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


        final int RECORDER_BPP = 16;

        long datasize=size+36;
        long byteRate = (RECORDER_BPP * RECORDER_SAMPLERATE)/8;
        long longSampleRate = RECORDER_SAMPLERATE;
        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (datasize & 0xff);
        header[5] = (byte) ((datasize >> 8) & 0xff);
        header[6] = (byte) ((datasize >> 16) & 0xff);
        header[7] = (byte) ((datasize >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1
        header[21] = 0;
        header[22] = (byte) 1;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) ((RECORDER_BPP) / 8);  // block align
        header[33] = 0;
        header[34] = RECORDER_BPP;  // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (size & 0xff);
        header[41] = (byte) ((size >> 8) & 0xff);
        header[42] = (byte) ((size >> 16) & 0xff);
        header[43] = (byte) ((size >> 24) & 0xff);
        // out.write(header, 0, 44);

        try {
            RandomAccessFile rFile = new RandomAccessFile(outpath, "rw");
            rFile.seek(0);
            rFile.write(header);
            rFile.close();
            if (callback != null){
                callback.onAudioOperationFinished();
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            if (callback != null){
                callback.onAudioOperationError(e);
            }
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            if (callback != null){
                callback.onAudioOperationError(e);
            }
            e.printStackTrace();
        }
    }

    public static void RawToWave(String rawfn, String wavefn) throws IOException {
        File rawFile = new File(rawfn);
        File waveFile = new File(wavefn);
        byte[] rawData = new byte[(int) rawFile.length()];
        DataInputStream input = null;
        try {
            input = new DataInputStream(new FileInputStream(rawFile));
            input.read(rawData);
        } finally {
            if (input != null) {
                input.close();
            }
        }

        DataOutputStream output = null;
        try {
            output = new DataOutputStream(new FileOutputStream(waveFile));
            // WAVE header
            // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
            AudioMediaOperation.writeString(output, "RIFF"); // chunk id
            AudioMediaOperation.writeInt(output, 36 + rawData.length); // chunk size
            AudioMediaOperation.writeString(output, "WAVE"); // format
            AudioMediaOperation.writeString(output, "fmt "); // subchunk 1 id
            AudioMediaOperation.writeInt(output, 16); // subchunk 1 size
            AudioMediaOperation.writeShort(output, (short) 1); // audio format (1 = PCM)
            AudioMediaOperation.writeShort(output, (short) 1); // number of channels
            AudioMediaOperation.writeInt(output, Constants.SAMPLE_RATE); // sample rate
            AudioMediaOperation.writeInt(output, Constants.SAMPLE_RATE * 2); // byte rate
            AudioMediaOperation.writeShort(output, (short) 2); // block align
            AudioMediaOperation.writeShort(output, (short) 16); // bits per sample
            AudioMediaOperation.writeString(output, "data"); // subchunk 2 id
            AudioMediaOperation.writeInt(output, rawData.length); // subchunk 2 size
            // Audio data (conversion big endian -> little endian)
            short[] shorts = new short[rawData.length / 2];
            ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
            ByteBuffer bytes = ByteBuffer.allocate(shorts.length * 2);
            for (short s : shorts) {
                bytes.putShort(s);
            }

            output.write(AudioMediaOperation.fullyReadFileToBytes(rawFile));
        } finally {
            if (output != null) {
                output.close();
            }
            File file = new File(rawfn);
            file.delete();
            Log.d("[Log]", "rawToWave: delete the raw file.");
        }
    }


    static public byte[] fullyReadFileToBytes(File f) throws IOException {
        int size = (int) f.length();
        byte bytes[] = new byte[size];
        byte tmpBuff[] = new byte[size];
        FileInputStream fis= new FileInputStream(f);
        try {

            int read = fis.read(bytes, 0, size);
            if (read < size) {
                int remain = size - read;
                while (remain > 0) {
                    read = fis.read(tmpBuff, 0, remain);
                    System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
                    remain -= read;
                }
            }
        }  catch (IOException e){
            throw e;
        } finally {
            fis.close();
        }

        return bytes;
    }
    static public void writeInt(final DataOutputStream output, final int value) throws IOException {
        output.write(value >> 0);
        output.write(value >> 8);
        output.write(value >> 16);
        output.write(value >> 24);
    }

    static public void writeShort(final DataOutputStream output, final short value) throws IOException {
        output.write(value >> 0);
        output.write(value >> 8);
    }

    static public void writeString(final DataOutputStream output, final String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            output.write(value.charAt(i));
        }
    }

}
