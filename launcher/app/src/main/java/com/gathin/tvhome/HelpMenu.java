package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Раздел «Помощь» настроек лаунчера: что делает каждая кнопка пульта. Только чтение — ничего не
 * меняет и никуда не ведёт, кроме стрелок пульта. Всё, что здесь перечислено, есть в коде
 * лаунчера; тексты те же, что во всём меню настроек, — русским прямо в коде.
 */
final class HelpMenu {
    private final Activity activity;
    private final LinearLayout list;
    private final float density;
    /** Куда ведёт стрелка влево: обратно к списку разделов настроек. */
    private final Runnable left;

    private HelpMenu(Activity activity, LinearLayout list, float density, Runnable left) {
        this.activity = activity;
        this.list = list;
        this.density = density;
        this.left = left;
    }

    static void install(Activity activity, LinearLayout list, float density, Runnable left) {
        new HelpMenu(activity, list, density, left).build();
    }

    private int dp(float value) { return Math.round(value * density); }
    private boolean animations(){return activity.getSharedPreferences(Config.SETTINGS_NAME,0).getBoolean("animations",true)&&android.animation.ValueAnimator.areAnimatorsEnabled();}

    private void build() {
        String label = activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString();
        String version = "";
        try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        String repository = AppUpdates.repository(activity);
        key(label + " " + version, repository.isEmpty() ? "GitHub ещё не настроен" : repository);
        android.view.View about = list.getChildAt(list.getChildCount() - 1);
        about.setOnClickListener(v -> {
            if (repository.isEmpty()) return;
            try { activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(repository))); }
            catch (RuntimeException unavailable) {
                android.widget.Toast.makeText(activity, "Браузер не найден", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        paragraph("Короткий путеводитель по пульту. Кнопки работают, когда лаунчер открыт; «мои кнопки» настраиваются отдельно — последний раздел.");

        section("Навигация");
        key("Стрелки", "Переход между плитками и кнопками.");
        key("ОК", "Запустить приложение или открыть раздел.");
        key("ОК зажат", "Меню плитки: перестановка, сведения о приложении.");
        key("MENU (≡)", "То же, что долгое ОК, на плитке.");
        key("Назад", "Закрыть меню, вернуться к списку разделов.");
        key("Home", "На главный экран.");
        key("Program +/− (Page +/−)", "Следующие/прежние обои, когда листается файл, папка или Aerial Views.");

        section("Верхняя панель");
        key("Входы", "HDMI, AV и антенна. Долгое нажатие — настройки входов.");
        key("VPN", "Попадаем в текущий VPN. Долгое нажатие — выбор текущего VPN.");
        key("Сон", "Таймер сна; во время проигрывания видео со звуком не стартует.");
        key("Голос", "Голосовой помощник. Долгое нажатие — настройки поиска.");
        key("Шестерёнка", "Меню настроек лаунчера. Долгое нажатие — настройки системы.");

        section("Голосовые команды");
        paragraph("Нажмите кнопку микрофона на пульте и скажите команду. «Открой» можно опускать.");
        key("«включи» / «выключи»", "Включить или сразу выключить ТВ.");
        key("«настройки»", "Спросит: настройки лаунчера или системы.");
        key("«таймер» или «сон»", "Меню таймера сна; «таймер на 20 минут», «выключи таймер» — задать прямо.");
        key("«открой <приложение>»", "Открыть любое приложение: «открой Виму», «Кинопоиск», «Ютуб».");
        key("«найди <что>»", "Поиск кино; «…в Нум», «…в Виме» — в нужном приложении, без названия — выбор.");
        key("«громче» / «тише»", "Прибавить или убавить звук; «громкость 20» — поставить 20%.");
        key("«без звука» / «включи звук»", "Заглушить и вернуть звук; «громкость на максимум».");
        key("«яркость N» / «подсветка N»", "Яркость картинки; если прошивка не даёт — откроет меню изображения.");
        key("«вход HDMI 2»", "Переключить вход; «антенна», «вход ТВ», «вернись на лаунчер».");
        key("«канал 5» / «ТВ 5»", "Перейти на тюнер и включить канал 5; есть «следующий канал».");
        key("«назад», «вверх», «вниз»", "Навигация голосом, работает в лаунчере.");
        key("«который час»", "Открыть полноэкранные часы.");
        key("«какая погода»", "Озвучить погоду из шапки.");
        key("«поищи в интернете X»", "Запрос X браузеру; «найди X» без «в интернете» — поиск кино.");
        key("«Алиса, X»", "Браузер Алисы примет запрос X и нажмёт ввод сам.");

        section("Перестановка плиток");
        paragraph("Вход в режим перетаскивания плиток долгим ОК.");
        key("«Перестановка» наверху", "Войти в режим (или «Переместить» из долгого ОК).");
        key("Стрелки", "Нести плитку по сетке.");
        key("ОК", "Поднять плитку, ещё ОК — опустить.");
        key("Долгое ОК или Назад", "Закончить и выйти из режима.");

        section("Обои");
        key("«Режим смены обоев»", "Плитки затухают за секунду. Канал +/− меняет обои, другая кнопка возвращает плитки; Назад выходит из режима.");
        key("Program +/−", "Переключение обоев.");

        section("Часы и затемнение");
        key("Полноэкранные часы", "Закрываются любой кнопкой пульта.");
        key("Затемнение", "Когда пульт молчит, экран плавно темнеет; задержка и скорость — в разделе «Затемнение».");

        section("Штатные кнопки");
        key("Громкость + / − и MUTE", "Как обычно: громкость и беззвучие.");
        key("Кнопка «Настройки» пульта", "Системные настройки ТВ (или быстрая панель, если она включена).");
        key("Остальные кнопки", "Уходят системе как есть.");

        section("Свои кнопки (config.xml)");
        key("Где настраивается", "В списке app_keys файла config.xml; собирается лаунчер заново на компьютере.");
        key("Пример", "190:wallpaper_next — кнопка с кодом 190 листает обои вперёд; KEYCODE_BLUE:wallpaper_prev — то же по имени кнопки, назад.");
        key("Слова действий", "back, home, recents, mute, sleep, screensaver, settings, wifi, wallpaper_next, wallpaper_prev, ignore.");
        key("Долгое нажатие", "Отдельный список hold: — если держать кнопку дольше секунды, срабатывает другое действие.");
        key("По скан-коду", "scan:490:слово — если пульт присылает не код кнопки, а скан-код.");
        key("Тонкость", "Кнопки, которые ТВ забирает себе раньше лаунчера, требуют системного помощника доступности — лаунчер умеет его включать.");
    }

    /** Заголовок раздела; под стрелки не попадает, поэтому не берёт фокус. */
    private void section(String title) {
        TextView text = text(title, 12, 0xFF9AA0A6);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18);
        params.bottomMargin = dp(6);
        text.setLayoutParams(params);
        text.setPadding(dp(20), 0, dp(20), 0);
        list.addView(text);
    }

    /** Пояснение перед первым разделом, тем же серым, что и заголовки. */
    private void paragraph(String text) {
        TextView view = text(text, 12, 0xFF9AA0A6);
        view.setPadding(dp(20), 0, dp(20), dp(6));
        list.addView(view);
    }

    /** Строчка «кнопка — что делает»: имя кнопки белой, пояснение серым под ним. */
    private void key(String name, String description) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(20), dp(10));
        row.setMinimumHeight(dp(60));
        row.setFocusable(true);
        row.setClickable(true);
        final TextView title = text(name, 14, 0xFFE8EAED);
        final TextView body = text(description, 12, 0xFF9AA0A6);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(3);
        row.addView(title);
        row.addView(body, bodyParams);
        // Как в строках основных настроек: вверх/вниз ходят по соседним строкам и никогда не
        // выпускают фокус за пределы списка, влево — обратно к разделам.
        row.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int step = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    for (int i = list.indexOfChild(row) + step; i >= 0 && i < list.getChildCount(); i += step)
                        if (list.getChildAt(i).isFocusable()) {
                            list.getChildAt(i).requestFocus();
                            break;
                        }
                }
                return true;
            }
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) left.run();
            return true;
        });
        row.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(background(focused ? 0xFFE8EAED : 0x00000000));
            title.setTextColor(focused ? 0xFF202124 : 0xFFE8EAED);
            body.setTextColor(focused ? 0xFF68696B : 0xFF9AA0A6);
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.05f : 1).scaleY(focused ? 1.05f : 1)
                    .translationZ(focused ? dp(12) : 0).setDuration(animations() ? 320 : 0)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        row.setLayoutParams(params);
        list.addView(row);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.SANS_SERIF);
        return text;
    }

    private GradientDrawable background(int color) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(8));
        return fill;
    }
}
