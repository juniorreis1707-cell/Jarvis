package com.jarvis;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Controls a real Chrome browser. Keeps a single browser instance open for
 * the whole run of the program (faster, and lets navigation keep context,
 * e.g. staying logged in across pages).
 */
public class BrowserTool {

    private static WebDriver driver;

    /** Creates the browser the first time any tool needs it. */
    private static WebDriver getDriver() {
        if (driver == null) {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            // Uncomment the line below to run Chrome without a visible window
            // (headless mode):
            // options.addArguments("--headless=new");
            driver = new ChromeDriver(options);
        }
        return driver;
    }

    public static String openPage(String url) {
        try {
            getDriver().get(url);
            return "Page opened successfully. Title: " + getDriver().getTitle();
        } catch (Exception e) {
            return "Error opening page: " + e.getMessage();
        }
    }

    /** Extracts the visible text of the current page, so the AI can "read" the content. */
    public static String extractText() {
        try {
            String text = getDriver().findElement(By.tagName("body")).getText();
            // Trim very long pages so we don't blow past the AI's context limit
            if (text.length() > 6000) {
                text = text.substring(0, 6000) + "\n... [text truncated, page too long]";
            }
            return text;
        } catch (Exception e) {
            return "Error extracting text: " + e.getMessage();
        }
    }

    /** Clicks the first link whose text contains the given snippet. */
    public static String clickLink(String linkText) {
        try {
            getDriver().findElement(By.partialLinkText(linkText)).click();
            return "Clicked link containing: \"" + linkText + "\". Current page: " + getDriver().getTitle();
        } catch (Exception e) {
            return "Error clicking link \"" + linkText + "\": " + e.getMessage();
        }
    }

    /** Closes the browser. Call this when the program shuts down. */
    public static void close() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }
}
