package com.hippo.ehviewer.ui.scene.download;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

import java.util.List;

public class DownloadLabelAdapter extends ArrayAdapter<DownloadLabelItem> {


    public DownloadLabelAdapter(@NonNull Context context, int resource, @NonNull List<DownloadLabelItem> objects) {
        super(context, resource, objects);
    }


    @SuppressLint("ViewHolder")
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View view;
        ViewHolder viewHolder;
        DownloadLabelItem item = getItem(position);
        if (convertView==null){
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_download_label_list, parent, false);
            viewHolder = new ViewHolder();
            viewHolder.textView1 = (TextView)view.findViewById(R.id.text1);
            viewHolder.textView2 = (TextView)view.findViewById(R.id.text2);
            view.setTag(viewHolder);
        }else {
            view = convertView;
            viewHolder = (ViewHolder) view.getTag();
        }

        DownloadLabelPresentation.bind(viewHolder.textView1, item.label, item.item);
        view.setContentDescription(item.label + ", " + item.count() + (item.item != null && item.item.getFolder()
                ? ", " + getContext().getString(com.hippo.ehviewer.Settings.isDownloadLabelExpanded(
                        item.item.getId(), item.item.getLabel()) ? R.string.download_label_action_collapse
                        : R.string.download_label_action_expand) : ""));
        viewHolder.textView2.setText(item.count());

        return view;
    }

    private static class ViewHolder{
        TextView textView1;
        TextView textView2;
        ViewHolder(){}
    }
}
