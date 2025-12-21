# Hướng dẫn sử dụng các sơ đồ Draw.io

## Danh sách các file sơ đồ

Thư mục này chứa các file `.drawio` có thể mở và chỉnh sửa trực tiếp trong [draw.io](https://app.diagrams.net/):

| File                                                         | Loại sơ đồ           | Mô tả                          |
| ------------------------------------------------------------ | -------------------- | ------------------------------ |
| [UseCase_Diagram.drawio](UseCase_Diagram.drawio)             | Use Case Diagram     | Sơ đồ ca sử dụng hệ thống      |
| [Architecture_Diagram.drawio](Architecture_Diagram.drawio)   | Architecture Diagram | Sơ đồ kiến trúc 5 lớp          |
| [Class_Diagram.drawio](Class_Diagram.drawio)                 | Class Diagram        | Sơ đồ lớp các thành phần chính |
| [Deployment_Diagram.drawio](Deployment_Diagram.drawio)       | Deployment Diagram   | Sơ đồ triển khai hệ thống      |
| [Sequence_PINShare.drawio](Sequence_PINShare.drawio)         | Sequence Diagram     | Luồng chia sẻ qua mã PIN       |
| [Sequence_P2PTransfer.drawio](Sequence_P2PTransfer.drawio)   | Sequence Diagram     | Luồng truyền file P2P (TLS)    |
| [State_Transfer.drawio](State_Transfer.drawio)               | State Diagram        | Sơ đồ trạng thái Transfer      |
| [Activity_FileTransfer.drawio](Activity_FileTransfer.drawio) | Activity Diagram     | Quy trình tải file hoàn chỉnh  |

## Cách sử dụng

### Cách 1: Mở trực tiếp trên web

1. Truy cập [https://app.diagrams.net/](https://app.diagrams.net/)
2. Chọn **File → Open from → Device**
3. Chọn file `.drawio` cần mở
4. Chỉnh sửa và lưu

### Cách 2: Sử dụng VS Code Extension

1. Cài extension **Draw.io Integration** (hediet.vscode-drawio)
2. Mở file `.drawio` trực tiếp trong VS Code
3. Chỉnh sửa bằng giao diện đồ họa

### Cách 3: Xuất ra hình ảnh

1. Mở file trong draw.io
2. Chọn **File → Export as → PNG/SVG/PDF**
3. Lưu hình ảnh để chèn vào báo cáo

## Ghi chú

-  Tất cả sơ đồ đã được thiết kế với màu sắc phân biệt rõ ràng
-  Mỗi sơ đồ có legend/chú thích giải thích các ký hiệu
-  Có thể copy/paste các element giữa các file

## Màu sắc sử dụng

| Màu             | Mã hex  | Ý nghĩa                    |
| --------------- | ------- | -------------------------- |
| Xanh dương nhạt | #dae8fc | Presentation Layer / UI    |
| Xanh lá nhạt    | #d5e8d4 | Service Layer / Thành công |
| Vàng nhạt       | #fff2cc | Network Layer              |
| Cam nhạt        | #ffe6cc | Relay Server               |
| Đỏ nhạt         | #f8cecc | Security Layer / Lỗi       |
| Tím nhạt        | #e1d5e7 | Data/Model Layer           |
| Xám             | #f5f5f5 | Enum / Decision            |
