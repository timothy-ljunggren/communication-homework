package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Wetterplaner
 *
 * Der Nutzer gibt eine beliebige Anzahl von Städtenamen ein
 * Das Programm ermittelt für jede Stadt die geografischen Koordinaten (Lat / Long) über Nominatim
 * Anschließender Aufruf von Open-Meteo um das Wette in der Stadt zu erhalten
 * Am Ende wird die Wetterübersicht für alle Städte ausgegeben
 *
 * Keine API-Schlüssel notwendig
 */
public class HTTPClient {
    private static final String NOMINATIM_URL =
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=";

    private static final String OPEN_METEO_URL =
            "https://api.open-meteo.com/v1/forecast"
            + "?current_weather=true"
            + "&latitude=%s&longitude=%s";

    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newHttpClient();

        System.out.println("=== Städte-Trip Wetterplaner ===");
        String input = Helper.readLine("Städte (kommagetrennt, z.B. Berlin,Paris,Tokyo)");
        String[] cities = input.split(",");

        System.out.println();

        for (String city : cities) {
            city = city.trim();
            if (city.isEmpty()) continue;

            System.out.println("=== " + city + " ===");

            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            HttpRequest geoRequest = HttpRequest.newBuilder()
                    .uri(URI.create(NOMINATIM_URL + encodedCity))
                    .GET()
                    .build();

            HttpResponse<String> geoResponse = client.send(geoRequest, HttpResponse.BodyHandlers.ofString());

            String geoJson = geoResponse.body();

            JsonArray geoArray = JsonParser.parseString(geoJson).getAsJsonArray();
            if (geoArray.isEmpty()) {
                System.out.println("Stadt nicht gefunden.\n");
                continue;
            }
            JsonObject geoObj = geoArray.get(0).getAsJsonObject();
            double lat = geoObj.get("lat").getAsDouble();
            double lon = geoObj.get("lon").getAsDouble();

            System.out.printf("Koordinaten: "+ "%.4f° N, %.4f° E\n", lat, lon);

            String weatherUrl = String.format(OPEN_METEO_URL, lat, lon);
            HttpRequest weatherRequest = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .GET()
                    .build();

            HttpResponse<String> weatherResponse = client.send(weatherRequest, HttpResponse.BodyHandlers.ofString());

            String weatherJson = weatherResponse.body();

            JsonObject weatherRoot = JsonParser.parseString(weatherJson).getAsJsonObject();
            JsonObject cw = weatherRoot.getAsJsonObject("current_weather");
            double temperature = cw.get("temperature").getAsDouble();
            double windspeed = cw.get("windspeed").getAsDouble();
            int weathercode = cw.get("weathercode").getAsInt();

            System.out.printf("Temperatur: %.1f °C%n", temperature);
            System.out.printf("Windstärke: %.1f km/h%n", windspeed);
            System.out.printf("Wetterlage: %s%n%n", describeWeatherCode(weathercode));
        }
    }

    private static String describeWeatherCode(int code) {
        if (code == 0) return "Klarer Himmel";
        if (code == 1) return "Überwiegend klar";
        if (code == 2) return "Teilweise bewölkt";
        if (code == 3) return "Bedeckt";
        if (code >= 45 && code <= 48) return "Nebel";
        if (code >= 51 && code <= 55) return "Nieselregen";
        if (code >= 61 && code <= 65) return "Regen";
        if (code >= 71 && code <= 75) return "Schneefall";
        if (code >= 80 && code <= 82) return "Regenschauer";
        if (code >= 95 && code <= 99) return "Gewitter";
        return "Unbekannt (Code " + code + ")";
    }
}

