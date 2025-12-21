package org.example.p2psharefile.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.example.p2psharefile.model.*;
import org.example.p2psharefile.service.P2PService;
import org.example.p2psharefile.service.PreviewGenerator;
import org.example.p2psharefile.network.RelayClient;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Module 8: MainController - Controller cho giao diện chính
 * 
 * Quản lý UI và tương tác với P2PService
 */
public class MainController implements P2PService.P2PServiceListener {
    
    // ========== FXML Components ==========
    
    // Header
    @FXML private Label peerNameLabel;
    @FXML private Label statusLabel;
    @FXML private Label statusDot;
    @FXML private Label peerCountLabel;
    
    // Tab 1: Peers List
    @FXML private ListView<PeerInfo> peerListView;
    
    // Tab 2: Chia sẻ file
    @FXML private Button addFileButton;
    @FXML private Button addDirectoryButton;
    @FXML private ListView<String> sharedFilesListView;
    @FXML private Label sharedFileCountLabel;
    
    // Tab 3: Tìm kiếm & Download
    @FXML private TextField searchField;
    @FXML private Button searchButton;
    @FXML private ListView<SearchResultItem> searchResultsListView;
    @FXML private Button previewButton;
    @FXML private Button downloadButton;
    @FXML private TextArea logTextArea;
    @FXML private Label logLabel;
    
    // Tab 4: Share Code (PIN)
    @FXML private ListView<FileInfo> pinShareFileListView;
    @FXML private VBox pinDisplayPanel;
    @FXML private Label pinLabel;
    @FXML private Label pinFileNameLabel;
    @FXML private Label pinExpiryLabel;
    @FXML private TextField pinInputField;
    @FXML private Button receiveButton;
    
    // Connection Mode Toggle
    @FXML private ToggleButton p2pModeToggle;
    @FXML private ToggleButton relayModeToggle;
    
    // ========== Data ==========
    
    private P2PService p2pService;
    private ObservableList<PeerInfo> peerList;
    private ObservableList<FileInfo> sharedFilesList;
    private ObservableList<String> sharedFilesDisplay;
    private ObservableList<SearchResultItem> searchResults;
    
    private String downloadDirectory = System.getProperty("user.home") + "/Downloads/";
    
    // Connection Mode: true = P2P only, false = Relay only
    private boolean isP2PMode = true;
    
    // PIN-related
    private ShareSession currentPINSession = null;
    private Timeline pinExpiryTimeline = null;
    
    /**
     * Class để hiển thị kết quả tìm kiếm
     */
    public static class SearchResultItem {
        private final FileInfo fileInfo;
        private final PeerInfo peerInfo;
        
        public SearchResultItem(FileInfo fileInfo, PeerInfo peerInfo) {
            this.fileInfo = fileInfo;
            this.peerInfo = peerInfo;
        }
        
        public FileInfo getFileInfo() { return fileInfo; }
        public PeerInfo getPeerInfo() { return peerInfo; }
        
        @Override
        public String toString() {
            return fileInfo.getFileName() + " (" + fileInfo.getFormattedSize() + 
                   ") - từ " + peerInfo.getDisplayName();
        }
    }
    
    /**
     * Initialize - được gọi tự động sau khi FXML load
     */
    @FXML
    public void initialize() {
        // Khởi tạo observable lists
        peerList = FXCollections.observableArrayList();
        sharedFilesList = FXCollections.observableArrayList();
        sharedFilesDisplay = FXCollections.observableArrayList();
        searchResults = FXCollections.observableArrayList();
        
        // Bind data vào UI
        peerListView.setItems(peerList);
        sharedFilesListView.setItems(sharedFilesDisplay);
        searchResultsListView.setItems(searchResults);
        pinShareFileListView.setItems(sharedFilesList);
        
        // Setup custom cell factory cho sharedFilesListView với nút Hủy
        setupSharedFilesListView();
        
        // Set PIN input field max length to 6 digits
        pinInputField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                pinInputField.setText(newValue.replaceAll("[^\\d]", ""));
            }
            if (newValue.length() > 6) {
                pinInputField.setText(newValue.substring(0, 6));
            }
        });
        
        // Selection listener cho search results để enable/disable preview/download buttons
        searchResultsListView.getSelectionModel().selectedItemProperty().addListener(
            (observable, oldValue, newValue) -> {
                boolean hasSelection = newValue != null;
                boolean isServiceReady = p2pService != null;
                
                // Download: luôn enable nếu có selection và service ready
                downloadButton.setDisable(!hasSelection || !isServiceReady);
                
                // Preview: luôn enable nếu có selection (relay sẽ hiển thị info dialog)
                previewButton.setDisable(!hasSelection || !isServiceReady);
            }
        );
        
        // Setup connection mode toggle buttons
        setupConnectionModeToggle();
        
        // Set default status label to P2P Mode (LAN)
        statusLabel.setText("P2P Mode (LAN)");
        statusLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold; -fx-font-size: 14;");
        if (statusDot != null) {
            statusDot.setStyle("-fx-text-fill: #10b981; -fx-font-size: 20;");
        }
        
        log("📱 Ứng dụng P2P Share File đã sẵn sàng!");
        log("📁 Thư mục download mặc định: " + downloadDirectory);
        
        // 🚀 TỰ ĐỘNG KẾT NỐI KHI KHỞI ĐỘNG
        Platform.runLater(() -> {
            autoConnect();
        });
    }
    
    /**
     * Tự động kết nối khi khởi động ứng dụng
     */
    private void autoConnect() {
        try {
            String displayName = "Peer_" + System.getProperty("user.name");
            
            // Port = 0 nghĩa là hệ thống tự động chọn port trống
            int port = 0;
            
            // Tạo và khởi động P2P Service
            p2pService = new P2PService(displayName, port);
            p2pService.addListener(this);
            
            // 🌐 ENABLE RELAY: Tự động khởi động relay server và client
            org.example.p2psharefile.relay.RelayStarter.startRelayInBackground(p2pService);
            
            p2pService.start();
            
            // Lấy port thực tế được assign
            int actualPort = p2pService.getActualPort();
            
            // Hiển thị tên peer ở header
            peerNameLabel.setText(displayName);
            
            // Enable các chức năng
            searchButton.setDisable(false);
            receiveButton.setDisable(false);
            
            log("✅ Đã tự động kết nối!");
            log("📡 Port: " + actualPort);
            log("🔐 Security: TLS + AES-256 + ECDSA");
            
        } catch (Exception e) {
            log("❌ Lỗi tự động kết nối: " + e.getMessage());
            updateStatus("Lỗi", "#dc2626");
        }
    }
    
    /**
     * Xử lý khi dừng ứng dụng (có thể gọi từ menu hoặc window close)
     */
    public void handleStop() {
        if (p2pService != null) {
            p2pService.stop();
            p2pService = null;
        }
        
        // Dừng Relay Server
        org.example.p2psharefile.relay.RelayStarter.stopRelay();
        
        // Xóa tên peer khỏi header
        peerNameLabel.setText("");
        
        // Reset UI
        searchButton.setDisable(true);
        previewButton.setDisable(true);
        downloadButton.setDisable(true);
        receiveButton.setDisable(true);
        
        peerList.clear();
        searchResults.clear();
        
        // Stop PIN expiry timer if running
        if (pinExpiryTimeline != null) {
            pinExpiryTimeline.stop();
            pinExpiryTimeline = null;
        }
        currentPINSession = null;
        pinDisplayPanel.setVisible(false);
        
        peerCountLabel.setText("0");
        
        log("🛑 Đã dừng P2P Service");
    }
    
    /**
     * Setup connection mode toggle buttons
     */
    private void setupConnectionModeToggle() {
        if (p2pModeToggle == null || relayModeToggle == null) {
            return; // Buttons chưa được inject
        }
        
        // Tạo toggle group để chỉ 1 button được chọn
        javafx.scene.control.ToggleGroup modeGroup = new javafx.scene.control.ToggleGroup();
        p2pModeToggle.setToggleGroup(modeGroup);
        relayModeToggle.setToggleGroup(modeGroup);
        
        // Default: P2P mode
        p2pModeToggle.setSelected(true);
        isP2PMode = true;
        
        // P2P mode handler
        p2pModeToggle.setOnAction(e -> {
            if (p2pModeToggle.isSelected()) {
                switchToP2PMode();
            } else {
                // Đảm bảo luôn có 1 mode được chọn
                p2pModeToggle.setSelected(true);
            }
        });
        
        // Relay mode handler
        relayModeToggle.setOnAction(e -> {
            if (relayModeToggle.isSelected()) {
                switchToRelayMode();
            } else {
                // Đảm bảo luôn có 1 mode được chọn
                relayModeToggle.setSelected(true);
            }
        });
    }
    
    /**
     * Chuyển sang chế độ P2P (LAN)
     */
    private void switchToP2PMode() {
        isP2PMode = true;
        
        // Cập nhật logic trong các services
        if (p2pService != null) {
            p2pService.setP2POnlyMode(true);
        }
        
        // Cập nhật UI
        updateModeUI();
        log("🔒 Đã chuyển sang chế độ P2P (Mạng LAN - Bảo mật cao)");
        log("   • Tìm kiếm: Chỉ trong mạng LAN");
        log("   • PIN Share: Chỉ với các máy trong LAN");
        log("   • Preview: Hỗ trợ đầy đủ");
    }
    
    /**
     * Chuyển sang chế độ Relay (Internet)
     */
    private void switchToRelayMode() {
        isP2PMode = false;
        
        // Cập nhật logic trong các services
        if (p2pService != null) {
            p2pService.setP2POnlyMode(false);
        }
        
        // Cập nhật UI
        updateModeUI();
        log("🌐 Đã chuyển sang chế độ Relay (Kết nối Internet)");
        log("   • Tìm kiếm: Qua relay server");
        log("   • PIN Share: Qua Internet");
        log("   • Preview: Không hỗ trợ (cần download)");
    }
    
    /**
     * Cập nhật UI dựa trên mode hiện tại
     */
    private void updateModeUI() {
        Platform.runLater(() -> {
            // Xóa danh sách peers và search results khi chuyển mode
            peerList.clear();
            searchResults.clear();
            peerCountLabel.setText("0 Peers");
            
            if (isP2PMode) {
                // P2P mode: Preview enabled, search only LAN
                statusLabel.setText("P2P Mode (LAN)");
                statusLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold; -fx-font-size: 14;");
                if (statusDot != null) {
                    statusDot.setStyle("-fx-text-fill: #10b981; -fx-font-size: 20;");
                }
                // Enable preview button khi có file selected
                if (previewButton != null) {
                    SearchResultItem selected = searchResultsListView.getSelectionModel().getSelectedItem();
                    previewButton.setDisable(selected == null);
                }
            } else {
                // Relay mode: Preview disabled, search qua relay
                statusLabel.setText("Relay Mode (Internet)");
                statusLabel.setStyle("-fx-text-fill: #3b82f6; -fx-font-weight: bold; -fx-font-size: 14;");
                if (statusDot != null) {
                    statusDot.setStyle("-fx-text-fill: #3b82f6; -fx-font-size: 20;");
                }
                // Disable preview button trong Relay mode
                if (previewButton != null) {
                    previewButton.setDisable(true);
                }
            }
        });
    }
    
    /**
     * Kiểm tra có phải đang ở P2P mode không
     */
    public boolean isP2PMode() {
        return isP2PMode;
    }
    
    /**
     * Xử lý khi nhấn nút Add File
     */
    @FXML
    private void handleAddFile() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để chia sẻ");
        File file = fileChooser.showOpenDialog(addFileButton.getScene().getWindow());
        
        if (file != null) {
            p2pService.addSharedFile(file);
            refreshSharedFiles();
            log("✓ Đã thêm file: " + file.getName());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Add Directory
     */
    @FXML
    private void handleAddDirectory() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục để chia sẻ");
        File directory = dirChooser.showDialog(addDirectoryButton.getScene().getWindow());
        
        if (directory != null) {
            p2pService.addSharedDirectory(directory);
            refreshSharedFiles();
            log("✓ Đã thêm thư mục: " + directory.getName());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Search
     */
    @FXML
    private void handleSearch() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showError("Vui lòng nhập từ khóa tìm kiếm");
            return;
        }
        
        searchResults.clear();
        searchButton.setDisable(true);
        log("🔍 Đang tìm kiếm: " + query);
        
        p2pService.searchFile(query);
    }
    
    /**
     * Xử lý khi nhấn nút Download
     */
    @FXML
    private void handleDownload() {
        SearchResultItem selected = searchResultsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Vui lòng chọn file cần download");
            return;
        }
        
        // Cho phép chọn thư mục download
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");
        dirChooser.setInitialDirectory(new File(downloadDirectory));
        File saveDir = dirChooser.showDialog(downloadButton.getScene().getWindow());
        
        if (saveDir != null) {
            downloadDirectory = saveDir.getAbsolutePath();
            p2pService.downloadFile(
                selected.getPeerInfo(),
                selected.getFileInfo(),
                downloadDirectory
            );
            log("📥 Đang download: " + selected.getFileInfo().getFileName());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Preview (UltraView)
     */
    @FXML
    private void handlePreview() {
        SearchResultItem selected = searchResultsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Vui lòng chọn file cần xem preview");
            return;
        }
        
        FileInfo fileInfo = selected.getFileInfo();
        PeerInfo peerInfo = selected.getPeerInfo();
        
        // Kiểm tra nếu peer là relay -> hiển thị thông tin cơ bản
        if ("relay".equals(peerInfo.getIpAddress())) {
            showRelayFileInfoDialog(fileInfo, peerInfo);
            return;
        }
        
        // Disable button tạm thời
        previewButton.setDisable(true);
        log("👁️ Đang tải preview cho: " + fileInfo.getFileName());
        
        // Request preview trong thread riêng để không block UI
        new Thread(() -> {
            try {
                // Lấy fileHash (SHA-256) - nếu chưa có thì tính từ checksum tạm thời
                String fileHash = fileInfo.getFileHash();
                if (fileHash == null || fileHash.isEmpty()) {
                    fileHash = fileInfo.getChecksum(); // Fallback
                }
                
                if (fileHash == null || fileHash.isEmpty()) {
                    Platform.runLater(() -> {
                        showError("File không có hash, không thể preview");
                        previewButton.setDisable(false);
                    });
                    return;
                }
                
                // Request manifest
                final String finalFileHash = fileHash;
                PreviewManifest manifest = p2pService.requestPreviewManifest(peerInfo, fileHash);
                
                if (manifest == null) {
                    Platform.runLater(() -> {
                        showWarning("Không có preview", "File này không có preview hoặc owner không cho phép preview");
                        previewButton.setDisable(false);
                    });
                    return;
                }
                
                // Hiển thị preview dialog
                Platform.runLater(() -> {
                    showPreviewDialog(fileInfo, peerInfo, manifest, finalFileHash);
                    previewButton.setDisable(false);
                });
                
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("Lỗi khi tải preview: " + e.getMessage());
                    previewButton.setDisable(false);
                    e.printStackTrace();
                });
            }
        }).start();
    }
    
    /**
     * Hiển thị dialog preview
     */
    private void showPreviewDialog(FileInfo fileInfo, PeerInfo peerInfo, PreviewManifest manifest, String fileHash) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("UltraView Preview - " + fileInfo.getFileName());
        dialog.setHeaderText("Xem trước file từ: " + peerInfo.getDisplayName() + " (đã được tạo sẵn từ file gốc)");
        
        // Content
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setStyle("-fx-background-color: white;");
        
        // File info (simplified - no hash/signature)
        VBox infoBox = new VBox(5);
        infoBox.getChildren().addAll(
            new Label("📄 File: " + fileInfo.getFileName()),
            new Label("📊 Size: " + fileInfo.getFormattedSize()),
            new Label("🏷️ Type: " + manifest.getMimeType())
        );
        infoBox.setStyle("-fx-padding: 10; -fx-background-color: #f0f4f8; -fx-background-radius: 5;");
        content.getChildren().add(infoBox);
        
        // Preview content
        TabPane previewTabs = new TabPane();
        previewTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        boolean hasRealPreview = false;
        
        // Thumbnail tab - CHỈ cho file IMAGE (không hiển thị cho PDF)
        boolean isImageFile = manifest.getMimeType() != null && manifest.getMimeType().startsWith("image/");
        if (isImageFile && manifest.hasPreviewType(PreviewManifest.PreviewType.THUMBNAIL)) {
            Tab thumbTab = new Tab("🖼️ Hình ảnh");
            thumbTab.setContent(createThumbnailPreview(peerInfo, fileHash));
            previewTabs.getTabs().add(thumbTab);
            hasRealPreview = true;
        }
        
        // Text snippet tab (LUÔN ƯU TIÊN HIỂN THỊ ĐẦU TIÊN)
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET)) {
            Tab textTab = new Tab("📄 Nội dung");
            textTab.setContent(createTextSnippetPreview(peerInfo, fileHash));
            // Insert ở đầu nếu có thumbnail, hoặc add bình thường
            if (hasRealPreview) {
                previewTabs.getTabs().add(1, textTab);
            } else {
                previewTabs.getTabs().add(textTab);
            }
            hasRealPreview = true;
        }
        
        // Archive listing tab
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.ARCHIVE_LISTING)) {
            Tab archiveTab = new Tab("📦 Danh sách file");
            archiveTab.setContent(createArchiveListingPreview(peerInfo, fileHash));
            previewTabs.getTabs().add(archiveTab);
            hasRealPreview = true;
        }
        
        // Metadata tab - CHỈ HIỂN THỊ NẾU KHÔNG CÓ PREVIEW THỰC SỰ
        if (!hasRealPreview) {
            Tab metadataTab = new Tab("ℹ️ Thông tin file");
            metadataTab.setContent(createMetadataPreview(manifest));
            previewTabs.getTabs().add(metadataTab);
        }
        
        if (previewTabs.getTabs().isEmpty()) {
            Label noPreview = new Label("⚠️ Không có preview cho file này.\n\nVui lòng tải về để xem nội dung.");
            noPreview.setStyle("-fx-font-size: 14px; -fx-text-fill: #666; -fx-padding: 20;");
            content.getChildren().add(noPreview);
        } else {
            content.getChildren().add(previewTabs);
        }
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        
        // Responsive dialog - larger and resizable
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(1000, 800);
        dialog.getDialogPane().setMinSize(800, 600);
        
        // Make content responsive
        content.prefWidthProperty().bind(dialog.getDialogPane().widthProperty().subtract(40));
        content.prefHeightProperty().bind(dialog.getDialogPane().heightProperty().subtract(100));
        
        if (!previewTabs.getTabs().isEmpty()) {
            previewTabs.prefWidthProperty().bind(content.widthProperty());
            previewTabs.prefHeightProperty().bind(content.heightProperty().subtract(120));
        }
        
        dialog.showAndWait();
    }
    
    /**
     * Tạo thumbnail preview
     */
    private javafx.scene.Node createThumbnailPreview(PeerInfo peerInfo, String fileHash) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        box.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        
        Label loadingLabel = new Label("⏳ Đang tải thumbnail...");
        box.getChildren().add(loadingLabel);
        
        // Wrap trong ScrollPane ngay từ đầu
        ScrollPane scrollPane = new ScrollPane(box);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");
        
        new Thread(() -> {
            try {
                PreviewContent content = p2pService.requestPreviewContent(
                    peerInfo, fileHash, PreviewManifest.PreviewType.THUMBNAIL
                );
                
                if (content != null) {
                    java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(content.getData());
                    java.awt.image.BufferedImage bufferedImage = javax.imageio.ImageIO.read(bais);
                    
                    if (bufferedImage != null) {
                        javafx.scene.image.Image fxImage = javafx.embed.swing.SwingFXUtils.toFXImage(bufferedImage, null);
                        
                        Platform.runLater(() -> {
                            box.getChildren().clear();
                            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(fxImage);
                            imageView.setPreserveRatio(true);
                            
                            // Chỉ giới hạn width, để height tự động theo tỷ lệ
                            // Nếu hình cao hơn dialog, ScrollPane sẽ cho scroll
                            imageView.setFitWidth(700);
                            // KHÔNG set fitHeight - để hình hiển thị đầy đủ theo tỷ lệ
                            
                            Label sizeLabel = new Label("📊 Kích thước gốc: " + content.getWidth() + "x" + content.getHeight() + " - " + content.getFormattedSize());
                            sizeLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #636e72; -fx-padding: 10 0 0 0;");
                            
                            box.getChildren().addAll(imageView, sizeLabel);
                        });
                    }
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    box.getChildren().clear();
                    box.getChildren().add(new Label("❌ Lỗi: " + e.getMessage()));
                });
            }
        }).start();
        
        return scrollPane;
    }
    
    /**
     * Tạo text snippet preview
     */
    private javafx.scene.Node createTextSnippetPreview(PeerInfo peerInfo, String fileHash) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        
        Label loadingLabel = new Label("⏳ Đang tải nội dung...");
        box.getChildren().add(loadingLabel);
        
        new Thread(() -> {
            try {
                PreviewContent content = p2pService.requestPreviewContent(
                    peerInfo, fileHash, PreviewManifest.PreviewType.TEXT_SNIPPET
                );
                
                if (content != null) {
                    String text = new String(content.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    
                    Platform.runLater(() -> {
                        box.getChildren().clear();
                        
                        TextArea textArea = new TextArea(text);
                        textArea.setEditable(false);
                        textArea.setWrapText(true);
                        textArea.setPrefRowCount(20);
                        textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; " +
                                        "-fx-font-size: 13px; " +
                                        "-fx-control-inner-background: #f8f9fa; " +
                                        "-fx-text-fill: #2d3436;");
                        
                        Label infoLabel = new Label("📊 Kích thước: " + content.getFormattedSize());
                        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #636e72; -fx-padding: 5 0 0 0;");
                        
                        box.getChildren().addAll(textArea, infoLabel);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    box.getChildren().clear();
                    box.getChildren().add(new Label("❌ Lỗi: " + e.getMessage()));
                });
            }
        }).start();
        
        ScrollPane scrollPane = new ScrollPane(box);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }
    
    /**
     * Tạo archive listing preview
     */
    private javafx.scene.Node createArchiveListingPreview(PeerInfo peerInfo, String fileHash) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        
        Label loadingLabel = new Label("⏳ Đang tải danh sách file...");
        box.getChildren().add(loadingLabel);
        
        new Thread(() -> {
            try {
                PreviewContent content = p2pService.requestPreviewContent(
                    peerInfo, fileHash, PreviewManifest.PreviewType.ARCHIVE_LISTING
                );
                
                if (content != null) {
                    String listing = new String(content.getData(), java.nio.charset.StandardCharsets.UTF_8);
                    
                    Platform.runLater(() -> {
                        box.getChildren().clear();
                        
                        Label headerLabel = new Label("📦 Danh sách file trong archive:");
                        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0 0 10 0;");
                        
                        TextArea textArea = new TextArea(listing);
                        textArea.setEditable(false);
                        textArea.setPrefRowCount(20);
                        textArea.setWrapText(false);
                        textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; " +
                                        "-fx-font-size: 12px; " +
                                        "-fx-control-inner-background: #f8f9fa;");
                        
                        Label infoLabel = new Label("📊 Kích thước: " + content.getFormattedSize());
                        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #636e72; -fx-padding: 5 0 0 0;");
                        
                        box.getChildren().addAll(headerLabel, textArea, infoLabel);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    box.getChildren().clear();
                    box.getChildren().add(new Label("❌ Lỗi: " + e.getMessage()));
                });
            }
        }).start();
        
        ScrollPane scrollPane = new ScrollPane(box);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }
    
    /**
     * Tạo metadata preview
     */
    private javafx.scene.Node createMetadataPreview(PreviewManifest manifest) {
        VBox box = new VBox(8);
        box.setPadding(new javafx.geometry.Insets(10));
        
        // Header giải thích
        Label headerLabel = new Label("📋 Thông tin chi tiết về file (từ manifest đã ký)");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 0 0 10 0;");
        box.getChildren().add(headerLabel);
        
        box.getChildren().addAll(
            new Label("📄 Tên file: " + manifest.getFileName()),
            new Label("📊 Kích thước: " + formatBytes(manifest.getFileSize())),
            new Label("🏷️ Loại: " + manifest.getMimeType()),
            new Label("🔐 Hash (SHA-256): " + manifest.getFileHash()),
            new Label("📅 Sửa đổi lần cuối: " + new java.util.Date(manifest.getLastModified())),
            new Label("👤 Chủ sở hữu: " + manifest.getOwnerPeerId()),
            new Label("⏰ Preview tạo lúc: " + new java.util.Date(manifest.getTimestamp()))
        );
        
        // Note về download
        Label noteLabel = new Label("\n💡 Lưu ý: Khi tải file, dữ liệu sẽ được mã hóa AES-256 và nén (nếu cần) trong quá trình truyền, sau đó tự động giải mã khi lưu vào máy bạn.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-font-style: italic; -fx-padding: 10 0 0 0;");
        box.getChildren().add(noteLabel);
        
        // Custom metadata
        if (!manifest.getMetadata().isEmpty()) {
            Label customLabel = new Label("\n📋 Metadata bổ sung:");
            customLabel.setStyle("-fx-font-weight: bold;");
            box.getChildren().add(customLabel);
            
            manifest.getMetadata().forEach((key, value) -> 
                box.getChildren().add(new Label("  • " + key + ": " + value))
            );
        }
        
        return new ScrollPane(box);
    }
    
    /**
     * Hiển thị dialog preview cho file từ Relay Server
     * Download file tạm và tạo preview giống như P2P
     */
    private void showRelayFileInfoDialog(FileInfo fileInfo, PeerInfo peerInfo) {
        // Kiểm tra xem có RelayFileInfo không
        if (fileInfo.getRelayFileInfo() == null) {
            showRelayBasicInfoDialog(fileInfo, peerInfo);
            return;
        }
        
        // Hiển thị dialog loading
        Dialog<Void> loadingDialog = new Dialog<>();
        loadingDialog.setTitle("Đang tải preview...");
        loadingDialog.setHeaderText("📡 Đang tải file từ Relay Server");
        
        ProgressIndicator progress = new ProgressIndicator();
        progress.setStyle("-fx-min-width: 50; -fx-min-height: 50;");
        
        VBox loadingContent = new VBox(15);
        loadingContent.setAlignment(javafx.geometry.Pos.CENTER);
        loadingContent.setPadding(new javafx.geometry.Insets(30));
        loadingContent.getChildren().addAll(
            progress,
            new Label("Đang tải " + fileInfo.getFileName() + " để xem preview...")
        );
        
        loadingDialog.getDialogPane().setContent(loadingContent);
        loadingDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        
        // Download trong background thread
        new Thread(() -> {
            try {
                // Tạo temp file
                File tempDir = new File(System.getProperty("java.io.tmpdir"), "p2p-preview");
                tempDir.mkdirs();
                File tempFile = new File(tempDir, fileInfo.getFileName());
                
                // Download từ relay
                RelayClient relayClient = p2pService.getRelayClient();
                if (relayClient == null) {
                    throw new Exception("RelayClient không khả dụng");
                }
                
                boolean success = relayClient.downloadFile(
                    fileInfo.getRelayFileInfo(), 
                    tempFile, 
                    null  // No progress listener for preview
                );
                
                if (!success || !tempFile.exists()) {
                    throw new Exception("Không thể tải file từ relay server");
                }
                
                // Tạo preview từ file local
                PreviewManifest manifest = PreviewGenerator.generateManifest(tempFile, peerInfo.getPeerId());
                
                Platform.runLater(() -> {
                    loadingDialog.close();
                    showRelayPreviewDialog(fileInfo, peerInfo, manifest, tempFile);
                });
                
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loadingDialog.close();
                    // Fallback về basic info dialog
                    showRelayBasicInfoDialog(fileInfo, peerInfo);
                    log("⚠ Không thể tải preview: " + e.getMessage());
                });
            }
        }).start();
        
        loadingDialog.showAndWait();
    }
    
    /**
     * Hiển thị dialog preview thực sự cho relay file (sau khi đã download)
     */
    private void showRelayPreviewDialog(FileInfo fileInfo, PeerInfo peerInfo, PreviewManifest manifest, File tempFile) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Preview - " + fileInfo.getFileName());
        dialog.setHeaderText("📡 Xem trước file từ Relay Server");
        
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setStyle("-fx-background-color: white;");
        
        // File info
        VBox infoBox = new VBox(5);
        infoBox.getChildren().addAll(
            new Label("📄 File: " + fileInfo.getFileName()),
            new Label("📊 Size: " + fileInfo.getFormattedSize()),
            new Label("👤 Từ: " + peerInfo.getDisplayName()),
            new Label("🏷️ Type: " + manifest.getMimeType())
        );
        infoBox.setStyle("-fx-padding: 10; -fx-background-color: #f0f4f8; -fx-background-radius: 5;");
        content.getChildren().add(infoBox);
        
        // Preview tabs
        TabPane previewTabs = new TabPane();
        previewTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        boolean hasRealPreview = false;
        
        // Image preview
        boolean isImageFile = manifest.getMimeType() != null && manifest.getMimeType().startsWith("image/");
        if (isImageFile) {
            Tab imageTab = new Tab("🖼️ Hình ảnh");
            imageTab.setContent(createLocalImagePreview(tempFile));
            previewTabs.getTabs().add(imageTab);
            hasRealPreview = true;
        }
        
        // Text/Document preview
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET)) {
            Tab textTab = new Tab("📄 Nội dung");
            textTab.setContent(createLocalTextPreview(tempFile, manifest));
            previewTabs.getTabs().add(textTab);
            hasRealPreview = true;
        }
        
        // Archive preview
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.ARCHIVE_LISTING)) {
            Tab archiveTab = new Tab("📦 Danh sách file");
            archiveTab.setContent(createLocalArchivePreview(tempFile));
            previewTabs.getTabs().add(archiveTab);
            hasRealPreview = true;
        }
        
        // Fallback metadata
        if (!hasRealPreview) {
            Tab metadataTab = new Tab("ℹ️ Thông tin");
            metadataTab.setContent(createMetadataPreview(manifest));
            previewTabs.getTabs().add(metadataTab);
        }
        
        content.getChildren().add(previewTabs);
        
        // Note về temp file
        Label noteLabel = new Label("💡 Preview được tạo từ file tạm. Nhấn 'Tải về' để lưu vĩnh viễn.");
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        content.getChildren().add(noteLabel);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().setPrefSize(700, 600);
        
        dialog.showAndWait();
        
        // Clean up temp file after dialog closed
        try {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * Tạo image preview từ file local
     */
    private javafx.scene.Node createLocalImagePreview(File imageFile) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        box.setAlignment(javafx.geometry.Pos.CENTER);
        
        try {
            Image image = new Image(imageFile.toURI().toString(), 500, 400, true, true);
            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(500);
            imageView.setFitHeight(400);
            
            box.getChildren().add(imageView);
            
            // Image dimensions
            Label dimLabel = new Label(String.format("Kích thước: %.0f x %.0f px", 
                image.getWidth(), image.getHeight()));
            dimLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            box.getChildren().add(dimLabel);
            
        } catch (Exception e) {
            box.getChildren().add(new Label("❌ Không thể hiển thị hình ảnh: " + e.getMessage()));
        }
        
        return new ScrollPane(box);
    }
    
    /**
     * Tạo text preview từ file local
     */
    private javafx.scene.Node createLocalTextPreview(File file, PreviewManifest manifest) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        
        try {
            String textContent = "";
            
            // Check if it's a PDF
            if (file.getName().toLowerCase().endsWith(".pdf")) {
                try {
                    org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file);
                    org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    stripper.setStartPage(1);
                    stripper.setEndPage(Math.min(5, doc.getNumberOfPages())); // First 5 pages
                    textContent = stripper.getText(doc);
                    doc.close();
                    
                    // Truncate if too long
                    if (textContent.length() > 5000) {
                        textContent = textContent.substring(0, 5000) + "\n\n... [Đã cắt bớt] ...";
                    }
                } catch (Exception e) {
                    textContent = "Không thể đọc PDF: " + e.getMessage();
                }
            } else {
                // Regular text file
                java.nio.file.Path path = file.toPath();
                java.util.List<String> lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
                
                int maxLines = Math.min(100, lines.size());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < maxLines; i++) {
                    sb.append(lines.get(i)).append("\n");
                }
                if (lines.size() > maxLines) {
                    sb.append("\n... [Còn ").append(lines.size() - maxLines).append(" dòng nữa] ...");
                }
                textContent = sb.toString();
            }
            
            TextArea textArea = new TextArea(textContent);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 12px;");
            textArea.setPrefHeight(400);
            
            box.getChildren().add(textArea);
            
        } catch (Exception e) {
            box.getChildren().add(new Label("❌ Không thể đọc nội dung: " + e.getMessage()));
        }
        
        return box;
    }
    
    /**
     * Tạo archive listing preview từ file local
     */
    private javafx.scene.Node createLocalArchivePreview(File archiveFile) {
        VBox box = new VBox(10);
        box.setPadding(new javafx.geometry.Insets(10));
        
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(archiveFile);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            
            ListView<String> listView = new ListView<>();
            int count = 0;
            while (entries.hasMoreElements() && count < 100) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String icon = entry.isDirectory() ? "📁" : "📄";
                String size = entry.isDirectory() ? "" : " (" + formatBytes(entry.getSize()) + ")";
                listView.getItems().add(icon + " " + entry.getName() + size);
                count++;
            }
            
            zipFile.close();
            
            Label countLabel = new Label("📦 " + listView.getItems().size() + " items trong archive");
            countLabel.setStyle("-fx-font-weight: bold;");
            
            box.getChildren().addAll(countLabel, listView);
            
        } catch (Exception e) {
            box.getChildren().add(new Label("❌ Không thể đọc archive: " + e.getMessage()));
        }
        
        return box;
    }
    
    /**
     * Hiển thị dialog thông tin cơ bản (fallback khi không thể tạo preview)
     */
    private void showRelayBasicInfoDialog(FileInfo fileInfo, PeerInfo peerInfo) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Thông tin File - " + fileInfo.getFileName());
        dialog.setHeaderText("📡 File từ Relay Server");
        
        VBox content = new VBox(12);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setStyle("-fx-background-color: white;");
        
        // Header
        Label headerLabel = new Label("📋 Thông tin file từ Relay Server");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #3b82f6;");
        content.getChildren().add(headerLabel);
        
        // File info
        VBox infoBox = new VBox(8);
        infoBox.setStyle("-fx-padding: 15; -fx-background-color: #f0f4f8; -fx-background-radius: 8;");
        
        infoBox.getChildren().addAll(
            new Label("📄 Tên file: " + fileInfo.getFileName()),
            new Label("📊 Kích thước: " + fileInfo.getFormattedSize()),
            new Label("👤 Người chia sẻ: " + peerInfo.getDisplayName())
        );
        
        if (fileInfo.getFileHash() != null && !fileInfo.getFileHash().isEmpty()) {
            String shortHash = fileInfo.getFileHash().length() > 16 
                ? fileInfo.getFileHash().substring(0, 16) + "..." 
                : fileInfo.getFileHash();
            infoBox.getChildren().add(new Label("🔐 Hash: " + shortHash));
        }
        
        content.getChildren().add(infoBox);
        
        // Note
        Label noteLabel = new Label(
            "⚠️ Không thể tạo preview cho file này.\n\n" +
            "📥 Nhấn 'Tải về' để download file về máy và xem nội dung.\n\n" +
            "💡 File được truyền an toàn qua HTTPS."
        );
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-padding: 10 0 0 0;");
        content.getChildren().add(noteLabel);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK);
        dialog.getDialogPane().setPrefSize(450, 350);
        
        dialog.showAndWait();
    }
    
    /**
     * Format bytes thành dạng dễ đọc
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB (%,d bytes)", bytes / 1024.0, bytes);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB (%,d bytes)", bytes / (1024.0 * 1024), bytes);
        return String.format("%.2f GB (%,d bytes)", bytes / (1024.0 * 1024 * 1024), bytes);
    }
    
    /**
     * Tạo mô tả dễ hiểu về các preview có sẵn
     */
    private String getPreviewDescription(PreviewManifest manifest) {
        List<String> features = new ArrayList<>();
        
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.THUMBNAIL)) {
            features.add("🖼️ Ảnh xem trước");
        }
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.TEXT_SNIPPET)) {
            features.add("📝 Nội dung văn bản");
        }
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.ARCHIVE_LISTING)) {
            features.add("📦 Danh sách file trong archive");
        }
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.PDF_PAGES)) {
            features.add("📄 Trang PDF");
        }
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.AUDIO_SAMPLE)) {
            features.add("🎵 Audio sample");
        }
        if (manifest.hasPreviewType(PreviewManifest.PreviewType.VIDEO_PREVIEW)) {
            features.add("🎬 Video preview");
        }
        
        if (features.isEmpty()) {
            // Chỉ có metadata
            return "📋 Nội dung xem trước: Thông tin cơ bản về file (tên, kích thước, loại, hash SHA-256)";
        } else {
            return "✨ Nội dung xem trước có sẵn: " + String.join(", ", features);
        }
    }
    
    /**
     * Setup ListView cho shared files với nút Hủy
     */
    private void setupSharedFilesListView() {
        sharedFilesListView.setCellFactory(param -> new javafx.scene.control.ListCell<String>() {
            private final javafx.scene.control.Button removeBtn = new javafx.scene.control.Button("❌ Hủy");
            private final javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(10);
            private final javafx.scene.control.Label label = new javafx.scene.control.Label();
            
            {
                removeBtn.setStyle("-fx-background-color: #ff4757; -fx-text-fill: white; " +
                                  "-fx-font-weight: bold; -fx-padding: 5 10; " +
                                  "-fx-background-radius: 5; -fx-cursor: hand;");
                removeBtn.setOnAction(event -> {
                    String item = getItem();
                    if (item != null && p2pService != null) {
                        // Tìm FileInfo tương ứng và xóa
                        for (FileInfo fileInfo : sharedFilesList) {
                            if (item.startsWith(fileInfo.getFileName())) {
                                p2pService.removeSharedFile(fileInfo);
                                refreshSharedFiles();
                                log("🗑️ Đã hủy chia sẻ: " + fileInfo.getFileName());
                                break;
                            }
                        }
                    }
                });
                
                javafx.scene.layout.Region region = new javafx.scene.layout.Region();
                javafx.scene.layout.HBox.setHgrow(region, javafx.scene.layout.Priority.ALWAYS);
                hbox.getChildren().addAll(label, region, removeBtn);
                hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            }
            
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    label.setText(item);
                    setGraphic(hbox);
                }
            }
        });
    }
    
    /**
     * Refresh danh sách file chia sẻ
     */
    private void refreshSharedFiles() {
        if (p2pService != null) {
            sharedFilesList.clear();
            sharedFilesList.addAll(p2pService.getSharedFiles());
            
            // Cập nhật display list với thông tin hash
            sharedFilesDisplay.clear();
            for (FileInfo fileInfo : sharedFilesList) {
                String displayText = fileInfo.getFileName() + " (" + fileInfo.getFormattedSize() + ")";
                
                // Thêm hash info nếu có
                if (fileInfo.getFileHash() != null) {
                    String shortHash = fileInfo.getFileHash().substring(0, 8);
                    displayText += " [" + shortHash + "...]";
                }
                
                sharedFilesDisplay.add(displayText);
            }
            
            sharedFileCountLabel.setText("Files: " + p2pService.getSharedFileCount());
        }
    }
    
    // ========== PIN Code Handlers ==========
    
    /**
     * Xử lý khi chọn file để tạo mã PIN chia sẻ
     */
    @FXML
    private void handleSelectFileForPIN() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để chia sẻ bằng mã PIN");
        File file = fileChooser.showOpenDialog(pinShareFileListView.getScene().getWindow());
        
        if (file != null) {
            // Add to shared files first (will upload to relay in background if in relay mode)
            p2pService.addSharedFile(file);
            refreshSharedFiles();
            
            // Tìm FileInfo đã được add (có thể có RelayFileInfo nếu upload xong)
            FileInfo fileInfo = null;
            for (FileInfo fi : p2pService.getSharedFiles()) {
                if (fi.getFileName().equals(file.getName()) && 
                    fi.getFilePath().equals(file.getAbsolutePath())) {
                    fileInfo = fi;
                    break;
                }
            }
            
            // Nếu không tìm thấy, tạo mới
            if (fileInfo == null) {
                fileInfo = new FileInfo(
                    file.getName(),
                    file.length(),
                    file.getAbsolutePath()
                );
            }
            
            // Create PIN code for this file (trong background thread vì có thể phải upload lên relay)
            final FileInfo finalFileInfo = fileInfo;
            log("⏳ Đang tạo mã PIN" + (isP2PMode ? "" : " (đang upload lên relay...)"));
            
            new Thread(() -> {
                try {
                    ShareSession session = p2pService.createSharePIN(finalFileInfo);
                    
                    Platform.runLater(() -> {
                        if (session != null) {
                            currentPINSession = session;
                            
                            // Display PIN in UI
                            pinLabel.setText(session.getPin());
                            pinFileNameLabel.setText(finalFileInfo.getFileName());
                            pinDisplayPanel.setVisible(true);
                            
                            // Start countdown timer
                            startPINExpiryTimer();
                            
                            log("🔑 Đã tạo mã PIN: " + session.getPin() + " cho file: " + finalFileInfo.getFileName());
                            showInfo("Mã PIN đã được tạo!\n\n" +
                                "Mã: " + session.getPin() + "\n" +
                                "File: " + finalFileInfo.getFileName() + "\n" +
                                "Hết hạn sau: 10 phút\n\n" +
                                (isP2PMode ? "Mã này đã được gửi tới tất cả peers." : "Mã này đã được lưu trên relay server."));
                        } else {
                            showError("Không thể tạo mã PIN");
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        showError("Lỗi khi tạo mã PIN: " + e.getMessage());
                        log("❌ Lỗi tạo PIN: " + e.getMessage());
                    });
                }
            }, "CreatePIN-" + file.getName()).start();
        }
    }
    
    /**
     * Xử lý khi nhấn nút hủy PIN
     */
    @FXML
    private void handleCancelPIN() {
        if (currentPINSession != null) {
            p2pService.cancelPIN(currentPINSession.getPin());
            currentPINSession = null;
            pinDisplayPanel.setVisible(false);
            
            if (pinExpiryTimeline != null) {
                pinExpiryTimeline.stop();
                pinExpiryTimeline = null;
            }
            
            log("❌ Đã hủy mã PIN");
        }
    }
    
    /**
     * Xử lý khi nhấn nút nhận file bằng mã PIN
     */
    @FXML
    private void handleReceiveByPIN() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        String pin = pinInputField.getText().trim();
        
        if (pin.isEmpty()) {
            showError("Vui lòng nhập mã PIN");
            return;
        }
        
        if (pin.length() != 6) {
            showError("Mã PIN phải có 6 chữ số");
            return;
        }
        
        // Chọn thư mục lưu file
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");
        dirChooser.setInitialDirectory(new File(downloadDirectory));
        File saveDir = dirChooser.showDialog(pinInputField.getScene().getWindow());
        
        if (saveDir != null) {
            downloadDirectory = saveDir.getAbsolutePath();
            
            try {
                p2pService.receiveByPIN(pin, downloadDirectory);
                log("📥 Đang tải file bằng mã PIN: " + pin);
                pinInputField.clear();
                showInfo("Đã bắt đầu tải file từ mã PIN: " + pin);
            } catch (IllegalArgumentException e) {
                // PIN không tìm thấy hoặc hết hạn
                showError(e.getMessage());
                log("❌ " + e.getMessage());
            } catch (Exception e) {
                showError("Lỗi khi nhận file: " + e.getMessage());
                log("❌ Lỗi nhận file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Bắt đầu đếm ngược thời gian hết hạn của PIN
     */
    private void startPINExpiryTimer() {
        if (pinExpiryTimeline != null) {
            pinExpiryTimeline.stop();
        }
        
        pinExpiryTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (currentPINSession != null) {
                if (currentPINSession.isExpired()) {
                    // PIN expired
                    pinExpiryLabel.setText("⏰ Đã hết hạn!");
                    pinExpiryLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    pinExpiryTimeline.stop();
                    
                    Platform.runLater(() -> {
                        showInfo("Mã PIN đã hết hạn");
                        pinDisplayPanel.setVisible(false);
                        currentPINSession = null;
                    });
                } else {
                    // Update remaining time
                    String timeLeft = currentPINSession.getRemainingTimeFormatted();
                    pinExpiryLabel.setText("⏱ Hết hạn sau: " + timeLeft);
                    pinExpiryLabel.setStyle("-fx-text-fill: #666;");
                }
            }
        }));
        
        pinExpiryTimeline.setCycleCount(Timeline.INDEFINITE);
        pinExpiryTimeline.play();
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Update status label with dot indicator
     */
    private void updateStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16;");
            
            // Update status dot color
            if (statusDot != null) {
                statusDot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 20;");
            }
        });
    }
    
    /**
     * Log message
     */
    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            );
            String logLine = "[" + timestamp + "] " + message;
            
            // Update TextArea (hidden, for compatibility)
            if (logTextArea != null) {
                logTextArea.appendText(logLine + "\n");
            }
            
            // Update visible Label
            if (logLabel != null) {
                String current = logLabel.getText();
                if (current == null || current.isEmpty()) {
                    logLabel.setText(logLine);
                } else {
                    // Keep last 15 lines
                    String[] lines = current.split("\n");
                    StringBuilder sb = new StringBuilder();
                    int start = Math.max(0, lines.length - 14);
                    for (int i = start; i < lines.length; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    sb.append(logLine);
                    logLabel.setText(sb.toString());
                }
            }
        });
    }
    
    /**
     * Show error dialog
     */
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Lỗi");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Show info dialog
     */
    private void showInfo(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Thông báo");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Show warning dialog
     */
    private void showWarning(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    // ========== P2PServiceListener Implementation ==========
    
    @Override
    public void onPeerDiscovered(PeerInfo peer) {
        Platform.runLater(() -> {
            if (!peerList.contains(peer)) {
                peerList.add(peer);
            }
            peerCountLabel.setText(peerList.size() + " Peers");
            log("✓ Phát hiện peer: " + peer.getDisplayName());
        });
    }
    
    @Override
    public void onPeerLost(PeerInfo peer) {
        Platform.runLater(() -> {
            peerList.remove(peer);
            peerCountLabel.setText(peerList.size() + " Peers");
            log("✗ Mất kết nối: " + peer.getDisplayName());
        });
    }
    
    @Override
    public void onSearchResult(SearchResponse response) {
        Platform.runLater(() -> {
            for (FileInfo file : response.getFoundFiles()) {
                searchResults.add(new SearchResultItem(file, response.getSourcePeer()));
            }
            
            // Enable download button ngay khi có kết quả đầu tiên
            if (!searchResults.isEmpty()) {
                downloadButton.setDisable(false);
            }
            
            log("📦 Tìm thấy " + response.getFoundFiles().size() + 
                " file từ " + response.getSourcePeer().getDisplayName());
        });
    }
    
    @Override
    public void onSearchComplete() {
        Platform.runLater(() -> {
            searchButton.setDisable(false);
            if (searchResults.isEmpty()) {
                log("⚠ Không tìm thấy file nào");
            } else {
                log("✓ Tìm kiếm hoàn tất: " + searchResults.size() + " kết quả");
                downloadButton.setDisable(false);
            }
        });
    }
    
    @Override
    public void onTransferProgress(String fileName, long bytesTransferred, long totalBytes) {
        Platform.runLater(() -> {
            int percent = (int) ((bytesTransferred * 100) / totalBytes);
            log("⏳ " + fileName + ": " + percent + "%");
        });
    }
    
    @Override
    public void onTransferComplete(String fileName, File file) {
        Platform.runLater(() -> {
            log("✅ Download hoàn tất: " + fileName);
            if (isP2PMode) {
                log("  🔓 Đã giải mã AES-256 và giải nén");
            } else {
                log("  🌐 Đã tải từ relay server");
            }
            log("  💾 Đã lưu: " + file.getAbsolutePath());
            String modeInfo = isP2PMode ? "Đã giải mã & giải nén (P2P)" : "Đã tải từ relay server";
            showInfo("Download thành công!\n\nFile: " + fileName + 
                    "\n" + modeInfo + "\nLưu tại: " + file.getAbsolutePath());
        });
    }
    
    @Override
    public void onTransferError(String fileName, Exception e) {
        Platform.runLater(() -> {
            log("❌ Lỗi download " + fileName + ": " + e.getMessage());
            showError("Lỗi khi download: " + e.getMessage());
        });
    }
    
    @Override
    public void onServiceStarted() {
        // Không cập nhật statusLabel ở đây vì đã set theo mode (P2P/Relay)
        // Chỉ log thông báo
        log("✅ Service đã khởi động");
    }
    
    @Override
    public void onServiceStopped() {
        // Không cần update status vì user đã tắt service
        log("🛑 Service đã dừng");
    }
}
