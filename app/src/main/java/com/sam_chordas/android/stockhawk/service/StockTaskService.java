package com.sam_chordas.android.stockhawk.service;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.gcm.GcmNetworkManager;
import com.google.android.gms.gcm.GcmTaskService;
import com.google.android.gms.gcm.TaskParams;
import com.sam_chordas.android.stockhawk.IConstants;
import com.sam_chordas.android.stockhawk.data.QuoteColumns;
import com.sam_chordas.android.stockhawk.data.QuoteProvider;
import com.sam_chordas.android.stockhawk.rest.Utils;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Created by sam_chordas on 9/30/15.
 * The GCMTask service is primarily for periodic tasks. However, OnRunTask can be called directly
 * and is used for the initialization and adding task as well.
 */
public class StockTaskService extends GcmTaskService {
    private final String LOG_TAG = StockTaskService.class.getSimpleName();
    private final String URL = "https://query.yahooapis.com/v1/public/yql?q=";
    private final String URL_APPEND = "&format=json&diagnostics=true&env=store%3A%2F%2Fdatatables.org%2Falltableswithkeys&callback=";
    private final String QUERY = "select * from yahoo.finance.quotes where symbol in (";
    private final String STOCK_SYMBOLS = "\"YHOO\",\"AAPL\",\"GOOG\",\"MSFT\")";
    private final String PARAM_INIT = "init";
    private final String PARAM_PERIODIC = "periodic";
    private final String PARAM_ADD = "add";
    private final String PARAM_SYMBOL = "symbol";
    private final String ENCODING = "UTF-8";

    private OkHttpClient client = new OkHttpClient();
    private Context mContext;
    private StringBuilder mStoredSymbols = new StringBuilder();
    private boolean isUpdate;

    public StockTaskService() {
    }

    public StockTaskService(Context context) {
        mContext = context;
    }

    @Override
    public int onRunTask(TaskParams params) {
        int result = GcmNetworkManager.RESULT_FAILURE;

        Cursor initQueryCursor;
        if (mContext == null) {
            mContext = this;
        }
        StringBuilder urlStringBuilder = new StringBuilder();
        try {
            // Base URL for the Yahoo query
            urlStringBuilder.append(URL);
            urlStringBuilder.append(URLEncoder.encode(QUERY, ENCODING));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            Utils.setStockErrorStatus(mContext, Utils.STATUS_UNKNOWN);
            return result;
        }
        if (params.getTag().equals(PARAM_INIT) || params.getTag().equals(PARAM_PERIODIC)) {
            isUpdate = true;
            initQueryCursor = mContext.getContentResolver().query(QuoteProvider.Quotes.CONTENT_URI,
                    new String[] { "Distinct " + QuoteColumns.SYMBOL }, null,
                    null, null);
            if (initQueryCursor == null || initQueryCursor.getCount() == 0) {
                // Init task. Populates DB with quotes for the symbols seen below
                try {
                    urlStringBuilder.append(
                            URLEncoder.encode(STOCK_SYMBOLS, ENCODING));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                    Utils.setStockErrorStatus(mContext, Utils.STATUS_UNKNOWN);
                    return result;
                }
            } else {
                DatabaseUtils.dumpCursor(initQueryCursor);
                initQueryCursor.moveToFirst();
                for (int i = 0; i < initQueryCursor.getCount(); i++) {

                    mStoredSymbols.append("\"")
                            .append(initQueryCursor.getString(initQueryCursor.getColumnIndex(PARAM_SYMBOL)))
                            .append("\",");

                    initQueryCursor.moveToNext();
                }
                mStoredSymbols.replace(mStoredSymbols.length() - 1, mStoredSymbols.length(), ")");
                try {
                    urlStringBuilder.append(URLEncoder.encode(mStoredSymbols.toString(), ENCODING));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                    Utils.setStockErrorStatus(mContext, Utils.STATUS_UNKNOWN);
                    return result;
                }
            }
        } else if (params.getTag().equals(PARAM_ADD)) {
            isUpdate = false;
            // get symbol from params.getExtra and build query
            String stockInput = params.getExtras().getString(PARAM_SYMBOL);
            try {
                urlStringBuilder.append(URLEncoder.encode("\"" + stockInput + "\")", ENCODING));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                Utils.setStockErrorStatus(mContext, Utils.STATUS_UNKNOWN);
                return result;
            }
        }
        // finalize the URL for the API query.
        urlStringBuilder.append(URL_APPEND);

        String urlString;
        String getResponse;

        urlString = urlStringBuilder.toString();
        try {
            getResponse = fetchData(urlString);
            try {
                ContentValues contentValues = new ContentValues();
                // update ISCURRENT to 0 (false) so new data is current
                if (isUpdate) {
                    contentValues.put(QuoteColumns.ISCURRENT, 0);
                    mContext.getContentResolver().update(QuoteProvider.Quotes.CONTENT_URI, contentValues,
                            null, null);
                }
                mContext.getContentResolver().applyBatch(QuoteProvider.AUTHORITY,
                        Utils.quoteJsonToContentVals(mContext, getResponse));

                // send broadcast so Widget can Update data
                Intent broadcastIntent = new Intent(IConstants.ACTION_STOCK_UPDATE)
                        .setPackage(mContext.getPackageName());
                mContext.sendBroadcast(broadcastIntent);
                result = GcmNetworkManager.RESULT_SUCCESS;

            } catch (RemoteException | OperationApplicationException e) {
                Log.e(LOG_TAG, "Error applying batch insert", e);
                Utils.setStockErrorStatus(mContext, Utils.STATUS_SERVER_ERROR);
                return result;
            }
        } catch (IOException e) {
            e.printStackTrace();
            Utils.setStockErrorStatus(mContext, Utils.STATUS_SERVER_DOWN);
            return result;
        }

        return result;
    }

    private String fetchData(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        Response response = client.newCall(request).execute();
        return response.body().string();
    }
}
