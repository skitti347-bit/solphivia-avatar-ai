package it.solphivia.mobile;

/** In-memory reading position only. Engines without range callbacks repeat the current chunk. */
final class ReadingContinuation {
    private static String text="";
    private static int offset;
    private static int rangeEnd;
    private static boolean preciseRange;
    private static long generation;
    private static boolean paused;
    static synchronized long begin(String value){text=value==null?"":value;offset=0;rangeEnd=0;preciseRange=false;paused=false;return ++generation;}
    static synchronized void progress(long token,int start){progress(token,start,start);}
    static synchronized void progress(long token,int start,int end){
        if(token!=generation||paused)return;
        offset=Math.max(0,Math.min(start,text.length()));
        rangeEnd=Math.max(offset,Math.min(end,text.length()));
        preciseRange=rangeEnd>offset;
    }
    static synchronized String echoContext(String fallback){
        if(text.isEmpty()||paused||!preciseRange)return fallback;
        int start=Math.max(0,offset-32);
        int end=Math.min(text.length(),rangeEnd+32);
        return text.substring(start,end);
    }
    static synchronized void pause(){if(!text.isEmpty())paused=true;}
    static synchronized void pause(long token){if(token==generation&&!text.isEmpty())paused=true;}
    static synchronized boolean available(){return paused&&!text.isEmpty();}
    static synchronized String sourceText(String fallback){return text.isEmpty()||paused?fallback:text;}
    static synchronized String take(){if(!available())return "";paused=false;return text.substring(offset);}
    static synchronized void complete(long token){if(token==generation&&!paused){text="";offset=0;}}
    static synchronized void clear(){text="";offset=0;paused=false;generation++;}
}
