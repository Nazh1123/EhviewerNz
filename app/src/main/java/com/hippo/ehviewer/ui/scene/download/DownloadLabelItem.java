package com.hippo.ehviewer.ui.scene.download;

public class DownloadLabelItem {
    String label;
    com.hippo.ehviewer.dao.DownloadLabel item;
    String folderCount;
    private long count;

    DownloadLabelItem(){

    }

    DownloadLabelItem(String label, long count){
        this.count = count;
        this.label = label;
    }

    public String count(){
        return folderCount != null ? folderCount : Long.toString(count);
    }

}
