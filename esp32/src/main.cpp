/**
 * ESP32-S3 Camera Node — V1 (§17)
 * Camera → JPEG → Wi-Fi → MJPEG/snapshot/status. No fatigue AI.
 */
#include <Arduino.h>
#include <WiFi.h>
#include <esp_camera.h>
#include <esp_http_server.h>

#define AP_SSID "DRIVER-CAM"
#define AP_PASS "drowsy123"
#define AP_CHANNEL 6

static httpd_handle_t stream_httpd = NULL;
static httpd_handle_t snapshot_httpd = NULL;

static esp_err_t status_handler(httpd_req_t *req) {
  char buf[256];
  snprintf(buf, sizeof(buf),
    "{\"uptime\":%lu,\"width\":%d,\"height\":%d,\"fps\":10,\"clients\":%d}",
    millis()/1000, 640, 480, WiFi.softAPgetStationNum());
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf, strlen(buf));
}

static esp_err_t snapshot_handler(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) { httpd_resp_send_500(req); return ESP_FAIL; }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return ESP_OK;
}

static esp_err_t stream_handler(httpd_req_t *req) {
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=frame");
  char part_hdr[64];
  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) continue;
    snprintf(part_hdr, sizeof(part_hdr),
      "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", fb->len);
    if (httpd_resp_send_chunk(req, part_hdr, strlen(part_hdr)) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, "\r\n", 2) != ESP_OK) { esp_camera_fb_return(fb); break; }
    esp_camera_fb_return(fb);
    vTaskDelay(100 / portTICK_PERIOD_MS); // ~10 FPS
  }
  return ESP_OK;
}

void startServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  httpd_handle_t server = NULL;
  if (httpd_start(&server, &config) == ESP_OK) {
    httpd_uri_t s1 = {.uri="/status", .method=HTTP_GET, .handler=status_handler, .user_ctx=NULL};
    httpd_uri_t s2 = {.uri="/snapshot", .method=HTTP_GET, .handler=snapshot_handler, .user_ctx=NULL};
    httpd_uri_t s3 = {.uri="/stream", .method=HTTP_GET, .handler=stream_handler, .user_ctx=NULL};
    httpd_register_uri_handler(server, &s1);
    httpd_register_uri_handler(server, &s2);
    httpd_register_uri_handler(server, &s3);
  }
}

void setup() {
  Serial.begin(115200);
  // Wi-Fi AP mode — direct demo (§18)
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, 0, 4);
  Serial.printf("AP %s at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  camera_config_t cfg{};
  cfg.ledc_channel = LEDC_CHANNEL_0; cfg.ledc_timer = LEDC_TIMER_0;
  cfg.pin_d0 = 5; cfg.pin_d1 = 18; cfg.pin_d2 = 19; cfg.pin_d3 = 21;
  cfg.pin_d4 = 36; cfg.pin_d5 = 39; cfg.pin_d6 = 34; cfg.pin_d7 = 35;
  cfg.pin_xclk = 0; cfg.pin_pclk = 22; cfg.pin_vsync = 25; cfg.pin_href = 23;
  cfg.pin_sccb_sda = 26; cfg.pin_sccb_scl = 27; cfg.pin_pwdn = 32; cfg.pin_reset = -1;
  cfg.xclk_freq_hz = 20000000; cfg.pixel_format = PIXFORMAT_JPEG;
  cfg.frame_size = FRAMESIZE_VGA; // 640x480 (§19)
  cfg.jpeg_quality = 12; // 0-63 lower=better, ~70 quality (spec 60-75 JPEG ≈ 12)
  cfg.fb_count = 2; cfg.fb_location = CAMERA_FB_IN_PSRAM; cfg.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t err = esp_camera_init(&cfg);
  if (err != ESP_OK) Serial.printf("Camera init failed %d\n", err);
  startServer();
}

void loop() { delay(1000); }
