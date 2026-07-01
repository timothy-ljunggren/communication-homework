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
        return switch (code) {
            case 0 -> "Klarer Himmel";
            case 1 -> "Überwiegend klar";
            case 2 -> "Teilweise bewölkt";
            case 3 -> "Bedeckt";
            case 45, 48 -> "Nebel";
            case 51 -> "Nieselregen: Leicht";
            case 53 -> "Nieselregen: Mäßig";
            case 55 -> "Nieselregen: Dicht";
            case 56 -> "Gefrierender Nieselregen: Leicht";
            case 57 -> "Gefrierender Nieselregen: Dicht";
            case 61 -> "Regen: Leicht";
            case 63 -> "Regen: Mäßig";
            case 65 -> "Regen: Stark";
            case 66 -> "Gefrierender Regen: Leicht";
            case 67 -> "Gefrierender Regen: Stark";
            case 71 -> "Schneefall: Leicht";
            case 73 -> "Schneefall: Mäßig";
            case 75 -> "Schneefall: Stark";
            case 77 -> "Schneekörner";
            case 80 -> "Regenschauer: Leicht";
            case 81 -> "Regenschauer: Mäßig";
            case 82 -> "Regenschauer: Heftig";
            case 85 -> "Schneeschauer: Leicht";
            case 86 -> "Schneeschauer: Stark";
            case 95 -> "Gewitter: Leicht bis mäßig";
            case 96, 99 -> "Gewitter mit Hagel";
            default -> "Unbekannt (Code " + code + ")";
        };
    }
}

