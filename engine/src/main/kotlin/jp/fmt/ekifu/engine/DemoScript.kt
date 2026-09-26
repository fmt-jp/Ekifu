package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants.DEMO_SCENE_SEC

/**
 * デモ再生の1区間。atSec になったら action を作曲に送る（反映は次の和音の切り替え）。
 * hint は画面に出す「聞きどころ」。
 */
class DemoCue(
    val atSec: Double,
    val title: String,
    val hint: String,
    val action: (ComposerInput) -> Unit,
)

class DemoScript(val cues: List<DemoCue>) {

    companion object {
        val PARK = Place("demo-park", "いつもの公園", Mood.CALM, themeSeed = 12345)
        val STATION = Place("demo-station", "駅", Mood.NOSTALGIC, themeSeed = 23456)
        val OFFICE = Place("demo-office", "職場", Mood.BRIGHT, themeSeed = 34567)

        private const val S = DEMO_SCENE_SEC

        /**
         * 架空の通勤（自宅 → 公園 → 駅 → 職場）。5分の場面を30秒に縮めている。
         * 始まりは実時間どおり1分。道中が2分続くと区切りが入る。
         */
        val COMMUTE = DemoScript(
            listOf(
                DemoCue(0.0, "自宅を出発",
                    "始まり：I→IV の繰り返し、音数は少なめ。夜明け前なので和音が1オクターブ低く、こもった音") {
                    it.setScene(Scene("demo01", Speed.WALK, Familiarity.FAMILIAR, SunLevel.NIGHT))
                },
                DemoCue(60.0, "家の近所",
                    "道中に入る：vi から始まる8つの和音の進行。空が白んで少し明るい音に") {
                    it.setScene(Scene("demo02", Speed.WALK, Familiarity.FAMILIAR, SunLevel.TWILIGHT))
                },
                DemoCue(60.0 + S, "大通りを歩く",
                    "場面の切り替え：ベルで新しい場所のモチーフ。昼になり音が開ける") {
                    it.setScene(Scene("demo03", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY))
                },
                DemoCue(60.0 + 2 * S, "初めての道",
                    "初めての場所：高いベルの飾りが時々入る") {
                    it.setScene(Scene("demo04", Speed.WALK, Familiarity.NEW, SunLevel.DAY))
                },
                DemoCue(60.0 + 3 * S, "信号待ち",
                    "止まっている：テンポが遅くなり（1拍1.1秒）、音数が減る") {
                    it.setScene(Scene("demo05", Speed.STILL, Familiarity.NORMAL, SunLevel.DAY))
                },
                DemoCue(60.0 + 4 * S, "また歩き出す",
                    "道中が2分続いたので区切り：IV→Vsus→IV→I を1回") {
                    it.setScene(Scene("demo06", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY))
                },
                DemoCue(60.0 + 6 * S, "いつもの公園に近づく",
                    "接近（落ち着く）：IV→Vsus→IV→I の繰り返し。公園のテーマがプラックで小さく鳴る。16拍かけてゆっくり、やわらかい音へ") {
                    it.approach(PARK)
                },
                DemoCue(60.0 + 7 * S, "いつもの公園に着く",
                    "到着：公園のテーマをベルで鳴らし、I を長く鳴らして着地。そのあと滞在（I→IV→vi→IV）") {
                    it.arrive(PARK)
                },
                DemoCue(60.0 + 8.5 * S, "公園を離れる",
                    "離れる：公園のテーマを一度だけ静かに鳴らし、16拍かけて道中に戻る") {
                    it.leave()
                    it.setScene(Scene("demo07", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY))
                },
                DemoCue(60.0 + 9.5 * S, "駅に近づく",
                    "接近（懐かしい）：駅のテーマ。ディレイの残響が長くなる") {
                    it.approach(STATION)
                },
                DemoCue(60.0 + 10.5 * S, "駅に着く",
                    "到着→滞在（懐かしい）：滞在の進行が vi→IV→I→iii に変わる") {
                    it.arrive(STATION)
                },
                DemoCue(60.0 + 12 * S, "電車に乗る",
                    "乗り物：テンポが速く（1拍0.8秒）、音数が増える") {
                    it.leave()
                    it.setScene(Scene("demo08", Speed.VEHICLE, Familiarity.NEW, SunLevel.DAY))
                },
                DemoCue(60.0 + 13 * S, "電車で移動",
                    "場面の切り替え：新しいモチーフ。24拍ごとに前の場面のモチーフが小さく聞こえる") {
                    it.setScene(Scene("demo09", Speed.VEHICLE, Familiarity.NORMAL, SunLevel.DAY))
                },
                DemoCue(60.0 + 14 * S, "職場の近くを歩く",
                    "なじみの場所：パッド中心で音数が減る") {
                    it.setScene(Scene("demo10", Speed.WALK, Familiarity.FAMILIAR, SunLevel.DAY))
                },
                DemoCue(60.0 + 15 * S, "職場に近づく",
                    "接近（明るい）：テンポが少し速く、メロディが高い音域まで広がり、ベルの飾りが増える") {
                    it.approach(OFFICE)
                },
                DemoCue(60.0 + 16 * S, "職場に着く",
                    "到着→滞在：このまま滞在が続く。停止ボタンで終わり（I を鳴らして約12秒でフェードアウト）") {
                    it.arrive(OFFICE)
                },
            ),
        )
    }
}
