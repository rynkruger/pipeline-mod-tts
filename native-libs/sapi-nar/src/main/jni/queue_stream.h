#ifndef QUEUE_STREAM_H_
#define QUEUE_STREAM_H_

class IStream;

//Producer/Consumer stream for single-threaded contexts 
struct QueueStream {
	virtual bool initialize() = 0;
	virtual IStream* getBaseStream() = 0; //pluggable to SAPI
	virtual void startWritingPhase() = 0;
	virtual void endWritingPhase() = 0;
	virtual void dispose() = 0;
	virtual int in_avail() = 0;
	virtual const char* nextChunk(int* size) = 0;
};

//TODO: create a derived class BufferQueueStream that uses a vector of char*,
//recycled after each writing phase (the vector would be not cleared).
//class BufferQueueStream : public QueueStream, public IStream{
//public:
//	IStream* getBaseStream(){
//		return this;
//	}
//private:
//	std::vector<char*> chunks_;
//	int lastChunkIndex_;
//	int offsetInLastChunk_;
//};

#define AUDIO_CHUNK_SIZE 4096

//Allocate a new Windows' Memory Stream for every writing phase
class WinQueueStream : public QueueStream{
public:
	WinQueueStream();

	IStream* getBaseStream();
	
	bool initialize();
	
	void startWritingPhase();
	
	void endWritingPhase();
	
	int in_avail();
	
	const char* nextChunk(int* size);
	
	void dispose();
	
private:
	char audio_[AUDIO_CHUNK_SIZE];
	IStream* base_;
};

#endif