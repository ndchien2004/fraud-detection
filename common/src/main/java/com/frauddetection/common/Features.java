package com.frauddetection.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The 5 real-time features computed for a transaction (section 5 of the spec). */
public record Features(
        @JsonProperty("so_giao_dich_5_phut") int soGiaoDich5Phut,
        @JsonProperty("tong_tien_1_gio") long tongTien1Gio,
        @JsonProperty("trung_binh_lich_su") double trungBinhLichSu,
        @JsonProperty("lech_so_voi_trung_binh") double lechSoVoiTrungBinh,
        @JsonProperty("khoang_cach_bat_thuong") boolean khoangCachBatThuong) {
}
