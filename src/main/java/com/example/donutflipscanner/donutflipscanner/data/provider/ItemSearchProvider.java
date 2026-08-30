package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ItemSearchResult;

import java.util.List;

public interface ItemSearchProvider {
    List<ItemSearchResult> search(String query);
}

