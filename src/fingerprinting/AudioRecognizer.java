package fingerprinting;

import serialization.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import utilities.HashingFunctions;
import utilities.Spectrum;

public class AudioRecognizer {

	// The main hashtable required in our interpretation of the algorithm to
	// store the song repository
	private Map<Long, List<KeyPoint>> hashMapSongRepository;

	// Variable to stop/start the listening loop
	public boolean running;

	// Constructor
	public AudioRecognizer() {

		// Deserialize the hash table hashMapSongRepository (our song repository)
		this.hashMapSongRepository = Serialization.deserializeHashMap();
		this.running = true;
	}

	// Method used to acquire audio from the microphone and to add/match a song fragment
	public void listening(String songId, boolean isMatching) throws LineUnavailableException {

		// Fill AudioFormat with the recording we want for settings
		AudioFormat audioFormat = new AudioFormat(AudioParams.sampleRate,
				AudioParams.sampleSizeInBits, AudioParams.channels,
				AudioParams.signed, AudioParams.bigEndian);

		// Required to get audio directly from the microphone and process it as an 
		// InputStream (using TargetDataLine) in another thread      
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
		final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
		line.open(audioFormat);
		line.start();

		Thread listeningThread = new Thread(new Runnable() {

			@Override
			public void run() {
				// Output stream 
				ByteArrayOutputStream outStream = new ByteArrayOutputStream();
				// Reader buffer
				byte[] buffer = new byte[AudioParams.bufferSize];               
				int n = 0;
				try {
					while (running) {
						// Reading
						int count = line.read(buffer, 0, buffer.length);
						// If buffer is not empty
						if (count > 0) {
							outStream.write(buffer, 0, count);
						}
					}

					byte[] audioTimeDomain = outStream.toByteArray();

					// Compute magnitude spectrum
					double [][] magnitudeSpectrum = Spectrum.compute(audioTimeDomain);                    
					// Determine the shazam action (add or matching) and perform it
					shazamAction(magnitudeSpectrum, songId, isMatching);                    
					// Close stream
					outStream.close();                    
					// Serialize again the hashMapSongRepository (our song repository)
					Serialization.serializeHashMap(hashMapSongRepository);                
				} catch (IOException e) {
					System.err.println("I/O exception " + e);
					System.exit(-1);
				}
			}
		});

		// Start listening
		listeningThread.start();

		System.out.println("Press ENTER key to stop listening...");
		try {
			System.in.read();
		} catch (IOException ex) {
			Logger.getLogger(AudioRecognizer.class.getName()).log(Level.SEVERE, null, ex);
		}
		this.running = false;               
	}   

	// Determine the shazam action (add or matching a song) and perform it 
	private void shazamAction(double[][] magnitudeSpectrum, String songId, boolean isMatching) {  

		// Hash table used for matching (Map<songId, Map<offset,count>>)
		Map<String, Map<Integer,Integer>> matchMap = 
				new HashMap<String, Map<Integer,Integer>>(); 

		// Iterate over all the chunks/ventanas from the magnitude spectrum
		for (int c = 0; c < magnitudeSpectrum.length; c++) { 
			// Compute the hash entry for the current chunk/ventana (magnitudeSpectrum[c])
			long hash = computeHashEntry(magnitudeSpectrum[c]);
			// ...            
			// In the case of adding the song to the repository
			if (!isMatching) { 
				//Create the new keyPoint with the id of the song and the current timestamp
				KeyPoint point = new KeyPoint(songId, c);
				//Create the list of keyPoints we are going to use to adding the new keyPoints
				List <KeyPoint> keyPointList;
				// Adding keypoint to the list in its relative hash entry which has been computed before
				//Checking if the song is already in our repository or not
				if((keyPointList = hashMapSongRepository.get(hash)) == null){
					//If the song isn't in our repository we initialize the list of keyPoints and we
					//add it with its hash as key
					keyPointList  = new ArrayList<KeyPoint>();
					hashMapSongRepository.put(hash, keyPointList);
				}
				//Both if the song is already in our repository or not we have to add the keyPoint to the keyPoint list
				keyPointList.add(point);
			}
			// In the case of matching a song fragment
			else {
				List <KeyPoint> keyPointList;
				if((keyPointList = hashMapSongRepository.get(hash)) != null) {
					// Iterate over the list of keypoints that matches the hash entry
					// in the the current chunk
					for(int i = 0; i < keyPointList.size(); i++) {
						// For each keypoint:
						// Compute the time offset (Math.abs(point.getTimestamp() - c))
						int offSet = Math.abs(keyPointList.get(i).getTimestamp() - c);
						// Tabla anidada temporal
						Map<Integer, Integer> tmp;
						// Now, focus on the matchMap hashtable:
						// If songId (extracted from the current keypoint) has not been found yet in the matchMap add it
						if((tmp = matchMap.get(keyPointList.get(i).getSongId())) == null) {
							//construir tabla anidada temporal para meterla (offset, 1)
							tmp = new HashMap<Integer, Integer>();
							tmp.put(offSet, 1);
							//añadir nueva entrada a matchmap
							matchMap.put(keyPointList.get(i).getSongId(), tmp);
							// (else) songId has been added in a past chunk	
						}else {
							// If this is the first time the computed offset appears for this particular songId
							if(tmp.get(offSet) == null){
								//Añadimos a la tabla anidada temporal the computed offset with a value of 1 (first time it appears)
								tmp.put(offSet, 1);
							// If this the computed offset has already appeared for this particular songId
							}else {
								//Añadimos a la tabla anidada temporal the computed offset incrementing its value by one unit
								tmp.put(offSet, tmp.get(offSet)+1);
							}
						}
					}
				}
			}            
		} // End iterating over the chunks/ventanas of the magnitude spectrum
		// If we chose matching, we 
		if (isMatching) {
			showBestMatching(matchMap);
		}
	}

	// Find out in which range the frequency is
	private int getIndex(int freq) {

		int i = 0;
		while (AudioParams.range[i] < freq) {
			i++;
		}
		return i;
	}  

	// Compute hash entry for the chunk/ventana spectra 
	private long computeHashEntry(double[] chunk) {

		// Variables to determine the hash entry for this chunk/window spectra
		double highscores[] = new double[AudioParams.range.length];
		int frequencyPoints[] = new int[AudioParams.range.length];

		for (int freq = AudioParams.lowerLimit; freq < AudioParams.unpperLimit - 1; freq++) {
			// Get the magnitude
			double mag = chunk[freq];
			// Find out which range we are in
			int index = getIndex(freq);
			// Save the highest magnitude and corresponding frequency:
			if (mag > highscores[index]) {
				highscores[index] = mag;
				frequencyPoints[index] = freq;
			}
		}        
		// Hash function 
		return HashingFunctions.hash1(frequencyPoints[0], frequencyPoints[1], 
				frequencyPoints[2],frequencyPoints[3],AudioParams.fuzzFactor);
	}

	// Method to find the songId with the most frequently/repeated time offset
	private void showBestMatching(Map<String, Map<Integer, Integer>> matchMap) {
		String song, bestSong = null;
		int offset, counter, bestCounter = 0;
		// Iterate over the songs in the hashtable used for matching (matchMap)
		Iterator songIterator = matchMap.entrySet().iterator();
		while(songIterator.hasNext()) {
			//We get the map entry in order to get the song name
			Map.Entry e = (Map.Entry)songIterator.next();
			//Get the song name
			song = (String) e.getKey();
			Map<Integer,Integer> tmp = (Map<Integer, Integer>) e.getValue();
			// (For each song) Iterate over the nested hashtable Map<offset,count>
			Iterator offsetIterator = tmp.entrySet().iterator();
			while(offsetIterator.hasNext()) {
				// Get the biggest offset for the current song and update (if necessary)
				// the best overall result found till the current iteration
				//bestSong = offset con mayor count
				Map.Entry d = (Map.Entry) offsetIterator.next();
				offset = (int) d.getKey();
				counter = tmp.get(offset);
				if(counter > bestCounter) {
					//We saving the possible best song
					bestCounter = counter;
					bestSong = song;
				}
			}
		}
		// Print the songId string which represents the best matching     
		System.out.println("Best song: " + bestSong);
	}
}