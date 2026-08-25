#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Plugin URL Updater - Eklenti Ana URL'lerini Otomatik Güncelleyici
Bu betik, tüm eklentilerin .kt dosyalarındaki mainUrl'yi günceller,
build.gradle.kts'deki version'ı artırır.
"""

import os
import re
import sys
from typing import List, Optional, Dict
from cloudscraper import CloudScraper

# ----------------------------------------------------------------------
# SABİTLER
# ----------------------------------------------------------------------

# İşleme alınmayacak klasör isimleri (kara liste)
EXCLUDED_DIRS = {
    "gradle", "build", "PasswordLock","TmdbProvider","Webteizle"
}

# ----------------------------------------------------------------------
# LOGLAMA YARDIMCILARI (Renkli konsol çıktısı)
# ----------------------------------------------------------------------

def log_info(msg: str) -> None:
    print(f"\033[94m[~] {msg}\033[0m")

def log_success(msg: str) -> None:
    print(f"\033[92m[+] {msg}\033[0m")

def log_error(msg: str) -> None:
    print(f"\033[91m[!] {msg}\033[0m")

def log_action(msg: str) -> None:
    print(f"\033[93m[»] {msg}\033[0m")

def log_warning(msg: str) -> None:
    print(f"\033[95m[⚠] {msg}\033[0m")

# ----------------------------------------------------------------------
# ANA SINIF
# ----------------------------------------------------------------------

class PluginUrlUpdater:
    def __init__(self, base_dir: str = ".") -> None:
        self.base_dir = base_dir
        self.session = CloudScraper()
        self.excluded = EXCLUDED_DIRS

    # ------------------------------------------------------------------
    # DOSYA BULMA (Büyük/Küçük Harf Duyarsız)
    # ------------------------------------------------------------------

    def _find_kt_file(self, directory: str, target_filename: str) -> Optional[str]:
        """
        Verilen dizin altında, belirtilen dosya adını (büyük/küçük harf duyarsız) arar.
        :param directory: Taranacak kök dizin
        :param target_filename: Aranan dosya adı (örn: 'DiziMom.kt')
        :return: Tam dosya yolu veya None
        """
        target_lower = target_filename.lower()
        for root, _, files in os.walk(directory):
            for file in files:
                if file.lower() == target_lower:
                    return os.path.join(root, file)
        return None

    # ------------------------------------------------------------------
    # EKLENTİ LİSTESİ OLUŞTURMA
    # ------------------------------------------------------------------

    def _get_plugin_directories(self) -> List[str]:
        """Hariç tutulanlar dışındaki tüm alt klasörleri döndürür."""
        dirs = []
        for item in os.listdir(self.base_dir):
            full_path = os.path.join(self.base_dir, item)
            if os.path.isdir(full_path) and not item.startswith(".") and item not in self.excluded:
                dirs.append(item)
        return sorted(dirs)

    def _get_kt_files(self) -> Dict[str, str]:
        """
        Her eklenti için .kt dosyasının tam yolunu bulur.
        :return: { 'eklenti_adi': '/tam/yol/ekt.kt', ... } sözlüğü
        """
        kt_map = {}
        for plugin in self._get_plugin_directories():
            kt_name = f"{plugin}.kt"
            file_path = self._find_kt_file(plugin, kt_name)
            if file_path:
                kt_map[plugin] = file_path
            else:
                log_warning(f"{plugin} klasöründe {kt_name} bulunamadı, atlanıyor.")
        return kt_map

    # ------------------------------------------------------------------
    # URL OKUMA ve GÜNCELLEME
    # ------------------------------------------------------------------

    def _get_current_main_url(self, kt_path: str) -> Optional[str]:
        """.kt dosyasından mainUrl değerini regex ile çıkarır."""
        try:
            with open(kt_path, "r", encoding="utf-8") as f:
                content = f.read()
            match = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', content)
            return match.group(1) if match else None
        except Exception as e:
            log_error(f"Dosya okuma hatası: {kt_path} - {e}")
            return None

    def _update_main_url(self, kt_path: str, old_url: str, new_url: str) -> None:
        """.kt dosyasındaki URL'yi değiştirir."""
        try:
            with open(kt_path, "r+", encoding="utf-8") as f:
                content = f.read()
                updated = content.replace(old_url, new_url)
                f.seek(0)
                f.write(updated)
                f.truncate()
        except Exception as e:
            log_error(f"URL güncelleme hatası: {kt_path} - {e}")

    # ------------------------------------------------------------------
    # VERSİYON ARTIRMA
    # ------------------------------------------------------------------

    def _increment_version(self, gradle_path: str) -> Optional[int]:
        """build.gradle.kts dosyasındaki version'ı bir artırır."""
        try:
            with open(gradle_path, "r+", encoding="utf-8") as f:
                content = f.read()
            match = re.search(r'version\s*=\s*(\d+)', content)
            if not match:
                return None
            old_ver = int(match.group(1))
            new_ver = old_ver + 1
            updated = content.replace(f"version = {old_ver}", f"version = {new_ver}")
            with open(gradle_path, "w", encoding="utf-8") as f:
                f.write(updated)
            return new_ver
        except Exception as e:
            log_error(f"Versiyon artırma hatası: {gradle_path} - {e}")
            return None

    # ------------------------------------------------------------------
    # HTTP İSTEĞİ (YÖNLENDİRMELERİ TAKİP EDER)
    # ------------------------------------------------------------------

    def _get_final_url(self, url: str) -> Optional[str]:
        """URL'ye GET atar, yönlendirmeler sonrası son URL'yi döndürür."""
        try:
            resp = self.session.get(url, allow_redirects=True, timeout=10)
            final = resp.url
            return final[:-1] if final.endswith("/") else final
        except Exception as e:
            log_error(f"HTTP isteği başarısız: {url} - {type(e).__name__}: {e}")
            return None

    # ------------------------------------------------------------------
    # ANA GÜNCELLEME METODU
    # ------------------------------------------------------------------

    def update_all(self) -> None:
        """Tüm eklentileri günceller."""
        kt_map = self._get_kt_files()
        if not kt_map:
            log_info("Hiçbir .kt dosyası bulunamadı. İşlem sonlandırılıyor.")
            return

        for plugin_name, kt_path in kt_map.items():
            log_info(f"Kontrol ediliyor: {plugin_name}")

            current_url = self._get_current_main_url(kt_path)
            if not current_url:
                log_error(f"mainUrl bulunamadı: {kt_path}")
                continue

            final_url = self._get_final_url(current_url)
            if not final_url:
                continue

            if current_url == final_url:
                log_success(f"Değişiklik yok: {current_url}")
                continue

            # URL değişti → güncelle
            self._update_main_url(kt_path, current_url, final_url)

            # build.gradle.kts'yi bul (önce plugin klasöründe dene)
            gradle_path = os.path.join(self.base_dir, plugin_name, "build.gradle.kts")
            if not os.path.isfile(gradle_path):
                # Alternatif: kt_path üzerinden üst dizinlere çık
                gradle_path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(kt_path)))), "build.gradle.kts")
                if not os.path.isfile(gradle_path):
                    log_warning(f"build.gradle.kts bulunamadı: {plugin_name}")
                    log_action(f"{current_url} → {final_url}  (versiyon artırılamadı)")
                    continue

            new_ver = self._increment_version(gradle_path)
            if new_ver is not None:
                log_action(f"{current_url} → {final_url}  (versiyon: {new_ver})")
            else:
                log_action(f"{current_url} → {final_url}  (versiyon artırılamadı)")

            print()  # Boş satır

# ----------------------------------------------------------------------
# KOMUT SATIRI GİRİŞ NOKTASI
# ----------------------------------------------------------------------

def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    updater = PluginUrlUpdater(base)
    updater.update_all()

if __name__ == "__main__":
    main()
