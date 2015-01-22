package org.daisy.pipeline.tts.synthesize;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;

import javax.xml.transform.URIResolver;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmSequenceIterator;

import org.daisy.pipeline.audio.AudioServices;
import org.daisy.pipeline.tts.AudioBufferTracker;
import org.daisy.pipeline.tts.TTSRegistry;
import org.daisy.pipeline.tts.TTSService.SynthesisException;
import org.daisy.pipeline.tts.config.ConfigReader;
import org.daisy.pipeline.tts.synthesize.TTSLog.ErrorCode;

import com.google.common.collect.Iterables;
import com.xmlcalabash.core.XProcRuntime;
import com.xmlcalabash.io.ReadablePipe;
import com.xmlcalabash.io.WritablePipe;
import com.xmlcalabash.library.DefaultStep;
import com.xmlcalabash.model.RuntimeValue;
import com.xmlcalabash.runtime.XAtomicStep;
import com.xmlcalabash.util.TreeWriter;

public class SynthesizeStep extends DefaultStep implements FormatSpecifications,
        IPipelineLogger {

	private ReadablePipe source = null;
	private ReadablePipe config = null;
	private WritablePipe result = null;
	private XProcRuntime mRuntime;
	private TTSRegistry mTTSRegistry;
	private Random mRandGenerator;
	private AudioServices mAudioServices;
	private Semaphore mStartSemaphore;
	private AudioBufferTracker mAudioBufferTracker;
	private URIResolver mURIresolver;
	private String mOutputDirOpt;
	private int mSentenceCounter = 0;

	private static String convertSecondToString(double seconds) {
		int iseconds = (int) (Math.floor(seconds));
		int milliseconds = (int) (Math.floor(1000 * (seconds - iseconds)));
		return String.format("%d:%02d:%02d.%03d", iseconds / 3600, (iseconds / 60) % 60,
		        (iseconds % 60), milliseconds);
	}

	public static XdmNode getFirstChild(XdmNode node) {
		XdmSequenceIterator iter = node.axisIterator(Axis.CHILD);
		if (iter.hasNext()) {
			return (XdmNode) iter.next();
		} else {
			return null;
		}
	}

	public SynthesizeStep(XProcRuntime runtime, XAtomicStep step, TTSRegistry ttsRegistry,
	        AudioServices audioServices, Semaphore startSemaphore,
	        AudioBufferTracker audioBufferTracker, URIResolver uriResolver) {
		super(runtime, step);
		mURIresolver = uriResolver;
		mStartSemaphore = startSemaphore;
		mAudioBufferTracker = audioBufferTracker;
		mAudioServices = audioServices;
		mRuntime = runtime;
		mTTSRegistry = ttsRegistry;
		mRandGenerator = new Random();
	}

	@Override
	synchronized public void printInfo(String message) {
		mRuntime.info(this, null, message);
	}

	@Override
	synchronized public void printDebug(String message) {
		if (mRuntime.getDebug()) {
			mRuntime.info(this, null, message);
		}
	}

	public void setInput(String port, ReadablePipe pipe) {
		if ("source".equals(port)) {
			source = pipe;
		} else if ("config".equals(port)) {
			config = pipe;
		}
	}

	public void setOutput(String port, WritablePipe pipe) {
		result = pipe;
	}

	@Override
	public void setOption(QName name, RuntimeValue value) {
		if ("output-dir".equals(name.getLocalName())) {
			mOutputDirOpt = value.getString();
		} else
			super.setOption(name, value);
	}

	public void reset() {
		source.resetReader();
		config.resetReader();
		result.resetWriter();
	}

	public void traverse(XdmNode node, SSMLtoAudio pool) throws SynthesisException {
		if (SentenceTag.equals(node.getNodeName())) {
			pool.dispatchSSML(node);
			if (++mSentenceCounter % 10 == 0){
				pool.endSection();
				mSentenceCounter = 0;
			}
		} else {
			XdmSequenceIterator iter = node.axisIterator(Axis.CHILD);
			while (iter.hasNext()) {
				traverse((XdmNode) iter.next(), pool);
			}
		}
	}

	public void run() throws SaxonApiException {
		super.run();

		try {
			mStartSemaphore.acquire();
		} catch (InterruptedException e) {
			mRuntime.error(e);
			return;
		}

		VoiceConfigExtension configExt = new VoiceConfigExtension();
		ConfigReader cr = new ConfigReader(mRuntime.getProcessor(), config.read(), configExt);

		String logEnabledProp = cr.getAllProperties().get("log");
		boolean logEnabled = "true".equalsIgnoreCase(logEnabledProp) && mOutputDirOpt != null
		        && !mOutputDirOpt.isEmpty();
		TTSLog log;
		if (logEnabled) {
			log = new TTSLogImpl();
		} else
			log = new TTSLogEmpty();

		String tmpDir = cr.getAllProperties().get("audio.tmpdir");
		if (tmpDir == null)
			tmpDir = System.getProperty("java.io.tmpdir");
		File audioOutputDir = null;
		do {
			String audioDir = tmpDir + "/";
			for (int k = 0; k < 2; ++k)
				audioDir += Long.toString(mRandGenerator.nextLong(), 32);
			audioOutputDir = new File(audioDir);
		} while (audioOutputDir.exists());
		audioOutputDir.mkdir();
		audioOutputDir.deleteOnExit();

		SSMLtoAudio ssmltoaudio = new SSMLtoAudio(audioOutputDir, mTTSRegistry, this,
		        mAudioBufferTracker, mRuntime.getProcessor(), mURIresolver, configExt, log);

		Iterable<SoundFileLink> soundFragments = Collections.EMPTY_LIST;
		try {
			while (source.moreDocuments()) {
				traverse(getFirstChild(source.read()), ssmltoaudio);
				ssmltoaudio.endSection();
			}
			Iterable<SoundFileLink> newfrags = ssmltoaudio.blockingRun(mAudioServices);
			soundFragments = Iterables.concat(soundFragments, newfrags);
		} catch (SynthesisException e) {
			mRuntime.error(e);
			return;
		} catch (InterruptedException e) {
			mRuntime.error(e);
			return;
		} finally {
			mStartSemaphore.release();
		}

		TreeWriter tw = new TreeWriter(runtime);
		tw.startDocument(runtime.getStaticBaseURI());
		tw.addStartElement(OutputRootTag);

		int num = 0;
		for (SoundFileLink sf : soundFragments) {
			String soundFileURI = sf.soundFileURIHolder.toString();
			if (!soundFileURI.isEmpty()) {
				tw.addStartElement(ClipTag);
				tw.addAttribute(Audio_attr_id, sf.xmlid);
				tw.addAttribute(Audio_attr_clipBegin, convertSecondToString(sf.clipBegin));
				tw.addAttribute(Audio_attr_clipEnd, convertSecondToString(sf.clipEnd));
				tw.addAttribute(Audio_attr_src, soundFileURI);
				tw.addEndElement();
				++num;
				TTSLog.Entry entry = log.getOrCreateEntry(sf.xmlid);
				entry.setSoundfile(soundFileURI);
				entry.setPositionInFile(sf.clipBegin, sf.clipEnd);
			} else {
				log.getOrCreateEntry(sf.xmlid).addError(
				        new TTSLog.Error(ErrorCode.AUDIO_MISSING,
				                "not synthesized or not encoded"));
			}
		}
		tw.addEndElement();
		tw.endDocument();
		result.write(tw.getResult());

		printInfo("number of synthesized sound fragments: " + num);
		printInfo("audio encoding unreleased bytes : "
		        + mAudioBufferTracker.getUnreleasedEncondingMem());
		printInfo("TTS unreleased bytes: " + mAudioBufferTracker.getUnreleasedTTSMem());

		/*
		 * Write the log file
		 */
		if (logEnabled) {
			printInfo("writing TTS logs...");
			TreeWriter xmlLog = new TreeWriter(runtime);
			xmlLog.startDocument(runtime.getStaticBaseURI());
			xmlLog.addStartElement(LogRootTag);
			xmlLog.startContent();
			for (TTSLog.Error err : log.readonlyGeneralErrors()) {
				writeXMLerror(xmlLog, err);
			}
			for (Map.Entry<String, TTSLog.Entry> entry : log.getEntries()) {
				TTSLog.Entry le = entry.getValue();
				xmlLog.addStartElement(LogTextTag);

				xmlLog.addAttribute(Log_attr_id, entry.getKey());
				if (le.getSoundFile() != null) {
					String basename = new File(le.getSoundFile()).getName();
					xmlLog.addAttribute(Log_attr_file, basename);
					xmlLog.addAttribute(Log_attr_begin, String.valueOf(le.getBeginInFile()));
					xmlLog.addAttribute(Log_attr_end, String.valueOf(le.getEndInFile()));
				}

				xmlLog.addAttribute(Log_attr_timeout, "" + le.getTimeout() + "s");

				if (le.getSelectedVoice() != null)
					xmlLog.addAttribute(Log_attr_selected_voice, le.getSelectedVoice()
					        .toString());
				if (le.getActualVoice() != null)
					xmlLog.addAttribute(Log_attr_actual_voice, le.getActualVoice().toString());

				for (TTSLog.Error err : le.getReadOnlyErrors())
					writeXMLerror(xmlLog, err);

				if (le.getSSML() != null) {
					xmlLog.addStartElement(LogSsmlTag);
					xmlLog.addSubtree(le.getSSML());
					xmlLog.addEndElement();
				}else{
					xmlLog.addText("No SSML available. This piece of text has probably been extracted from inside another sentence.");
				}

				if (le.getTTSinput() != null && !le.getTTSinput().isEmpty()) {
					for (String inp : le.getTTSinput()) {
						xmlLog.addStartElement(LogInpTag);
						xmlLog.addText(inp);
						xmlLog.addEndElement();
					}
				}

				xmlLog.addEndElement(); //LogTextTag
			}

			xmlLog.addEndElement(); //root
			xmlLog.endDocument();
			String content = xmlLog.getResult().toString();

			FileWriter fw = null;

			File outputdir;
			try {
				outputdir = new File(new URI(mOutputDirOpt).getPath());
				if (!outputdir.exists()) {
					outputdir.mkdirs();
				}
				File output = new File(outputdir, "tts-log.xml");
				try {

					fw = new FileWriter(output);
				} catch (IOException e) {
					printInfo("Cannot open log file " + output.getAbsolutePath());
				}
				if (fw != null) {
					try {
						fw.write(content);
					} catch (IOException e) {
						printInfo("Cannot write in log file " + output.getAbsolutePath());
					}
					try {
						fw.close();
					} catch (IOException e) {
						//ignore
					}
				}

			} catch (URISyntaxException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

		}
	}

	private static void writeXMLerror(TreeWriter tw, TTSLog.Error err) {
		tw.addStartElement(LogErrorTag);
		tw.addAttribute(Log_attr_code, err.getErrorCode().toString());
		tw.addText(err.getMessage());
		tw.addEndElement();
	}
}
