module org.example.p2psharefile {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    
    // Bouncy Castle for TLS certificate generation
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    
    // Java Desktop (for ImageIO, BufferedImage in UltraView)
    requires java.desktop;
    
    // PDF processing
    requires org.apache.pdfbox;
    
    // Audio metadata extraction
    requires jaudiotagger;
    requires java.logging;
    requires jdk.httpserver;

    // Mở package cho JavaFX để có thể sử dụng reflection
    opens org.example.p2psharefile to javafx.fxml;
    opens org.example.p2psharefile.controller to javafx.fxml;
    opens org.example.p2psharefile.model to javafx.fxml;
    
    // Export các package cần thiết
    exports org.example.p2psharefile;
    exports org.example.p2psharefile.controller;
    exports org.example.p2psharefile.model;
    exports org.example.p2psharefile.service;
    exports org.example.p2psharefile.network;
    exports org.example.p2psharefile.security;
    exports org.example.p2psharefile.compression;
    exports org.example.p2psharefile.relay;
    exports org.example.p2psharefile.test;
}