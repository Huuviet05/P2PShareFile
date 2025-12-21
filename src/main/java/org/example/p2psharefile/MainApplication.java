package org.example.p2psharefile;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * MainApplication - Ứng dụng chính P2P Share File
 * 
 * Đây là entry point của ứng dụng JavaFX
 */
public class MainApplication extends Application {
    
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(
            MainApplication.class.getResource("main-view.fxml")
        );
        
        Scene scene = new Scene(fxmlLoader.load(), 1100, 750);
        
        // Load custom CSS stylesheet
        String css = MainApplication.class.getResource("styles.css").toExternalForm();
        scene.getStylesheets().add(css);
        
        stage.setTitle("P2P ShareFile - Modern File Sharing");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(550);
        
        // Xử lý khi đóng app
        stage.setOnCloseRequest(event -> {
            System.out.println("👋 Đang đóng ứng dụng...");
            System.exit(0);
        });
        
        stage.show();
        
        System.out.println("=".repeat(60));
        System.out.println("🎉 P2P SHARE FILE APPLICATION");
        System.out.println("=".repeat(60));
        System.out.println("📚 Ứng dụng chia sẻ file Peer-to-Peer");
        System.out.println("✨ Tính năng:");
        System.out.println("   - P2P Mode: Kết nối trực tiếp LAN (TCP + TLS)");
        System.out.println("   - Relay Mode: Kết nối qua Internet (HTTP Relay)");
        System.out.println("   - File Transfer với Compression & Encryption");
        System.out.println("   - PIN Code Sharing (Send Anywhere style)");
        System.out.println("   - UltraView Preview (Image, PDF, Archive)");
        System.out.println("   - Security: TLS + AES-256 + ECDSA Signatures");
        System.out.println("=".repeat(60));
    }

    public static void main(String[] args) {
        launch();
    }
}
