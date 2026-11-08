package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Original AM-DECOMP queue geometries ported to native Compose vector paths.
 * The Compose SVG painter throws on Android; ImageVector works in both hosts.
 * Preserve fill alpha, path fill rules and 28-unit geometry exactly.
 */
internal object AppleQueueVectors {
    val Favorite: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_queue_favorite", defaultWidth = 28.dp, defaultHeight = 28.dp,
            viewportWidth = 28f, viewportHeight = 28f,
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "M14,14m-14,0a14,14 0,1 1,28 0a14,14 0,1 1,-28 0"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 0.19f,
                pathFillType = PathFillType.EvenOdd,
            )
            addPath(
                pathData = addPathNodes(
                    "M9.225,20.866C9.421,21.017 9.638,21.072 9.876,21.034C10.114,20.995 10.364,20.88 10.626,20.688L14" +
                    ".075,18.152L17.521,20.688C17.785,20.88 18.036,20.995 18.274,21.034C18.512,21.072 18.729,21.017 1" +
                    "8.925,20.866C19.119,20.72 19.236,20.529 19.277,20.292C19.318,20.055 19.286,19.785 19.182,19.482L" +
                    "17.818,15.435L21.294,12.94C21.561,12.751 21.747,12.549 21.854,12.335C21.962,12.121 21.975,11.897" +
                    " 21.896,11.665C21.818,11.435 21.673,11.264 21.461,11.152C21.25,11.041 20.982,10.987 20.658,10.99" +
                    "2L16.396,11.022L15.101,6.948C15,6.636 14.865,6.397 14.694,6.23C14.523,6.064 14.317,5.981 14.075," +
                    "5.981C13.834,5.981 13.627,6.064 13.457,6.23C13.286,6.397 13.15,6.636 13.05,6.948L11.754,11.022L7" +
                    ".489,10.992C7.167,10.987 6.901,11.041 6.689,11.152C6.477,11.264 6.332,11.434 6.255,11.662C6.175," +
                    "11.894 6.189,12.118 6.298,12.333C6.406,12.549 6.592,12.751 6.856,12.94L10.333,15.435L8.969,19.48" +
                    "2C8.864,19.785 8.833,20.055 8.875,20.292C8.917,20.529 9.034,20.72 9.225,20.866ZM10.808,18.692C10" +
                    ".799,18.683 10.797,18.666 10.804,18.641L11.994,15.394C12.048,15.246 12.079,15.119 12.086,15.015C" +
                    "12.093,14.91 12.066,14.815 12.006,14.729C11.945,14.644 11.844,14.554 11.703,14.461L8.832,12.53C8" +
                    ".807,12.516 8.797,12.501 8.801,12.485C8.804,12.474 8.822,12.468 8.856,12.468L12.308,12.588C12.54" +
                    "7,12.595 12.724,12.561 12.838,12.485C12.952,12.41 13.04,12.256 13.101,12.024L14.044,8.702C14.051" +
                    ",8.672 14.062,8.657 14.075,8.657C14.089,8.657 14.099,8.672 14.106,8.702L15.049,12.024C15.111,12." +
                    "256 15.199,12.41 15.314,12.485C15.429,12.561 15.606,12.595 15.846,12.588L19.301,12.468C19.333,12" +
                    ".468 19.35,12.474 19.353,12.485C19.357,12.499 19.347,12.514 19.322,12.53L16.447,14.464C16.306,14" +
                    ".558 16.206,14.647 16.146,14.733C16.087,14.818 16.06,14.913 16.066,15.016C16.072,15.12 16.102,15" +
                    ".246 16.157,15.394L17.346,18.641C17.353,18.666 17.352,18.683 17.343,18.692C17.336,18.701 17.32,1" +
                    "8.698 17.295,18.682L14.574,16.542C14.383,16.394 14.216,16.32 14.075,16.32C13.934,16.32 13.768,16" +
                    ".394 13.576,16.542L10.855,18.682C10.83,18.698 10.814,18.701 10.808,18.692Z"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
    }

    val Favorited: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_queue_favorited", defaultWidth = 28.dp, defaultHeight = 28.dp,
            viewportWidth = 28f, viewportHeight = 28f,
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "M14,0C21.732,0 28,6.268 28,14C28,21.732 21.732,28 14,28C6.268,28 0,21.732 0,14C0,6.268 6.268,0 1" +
                    "4,0ZM14.077,5.797C13.66,5.797 13.66,5.797 13.367,6.077C13.074,6.358 13.074,6.358 12.909,6.875L11" +
                    ".649,10.844L7.496,10.811C6.969,10.804 6.969,10.804 6.609,10.996C6.249,11.187 6.249,11.187 6.114," +
                    "11.58C5.975,11.979 5.975,11.979 6.158,12.347C6.341,12.715 6.341,12.715 6.778,13.029L10.161,15.45" +
                    "5L8.832,19.393C8.66,19.893 8.66,19.893 8.734,20.296C8.807,20.699 8.807,20.699 9.134,20.95C9.472," +
                    "21.208 9.472,21.208 9.877,21.147C10.283,21.085 10.283,21.085 10.718,20.765L14.077,18.295L17.428," +
                    "20.765C17.868,21.085 17.868,21.085 18.273,21.147C18.679,21.208 18.679,21.208 19.016,20.95C19.349" +
                    ",20.699 19.349,20.699 19.42,20.296C19.49,19.893 19.49,19.893 19.318,19.393L17.989,15.455L21.372," +
                    "13.029C21.814,12.715 21.814,12.715 21.995,12.35C22.176,11.985 22.176,11.985 22.036,11.585C21.901" +
                    ",11.187 21.901,11.187 21.542,10.996C21.182,10.804 21.182,10.804 20.649,10.811L16.502,10.844L15.2" +
                    "41,6.875C15.076,6.358 15.076,6.358 14.783,6.077C14.49,5.797 14.49,5.797 14.077,5.797Z"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            )
        }.build()
    }

    val More: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_queue_more", defaultWidth = 28.dp, defaultHeight = 28.dp,
            viewportWidth = 28f, viewportHeight = 28f,
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "M14,14m-14,0a14,14 0,1 1,28 0a14,14 0,1 1,-28 0"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                pathFillType = PathFillType.EvenOdd,
            )
            addPath(
                pathData = addPathNodes(
                    "M12,8C12,6.8954 12.8954,6 14,6C15.1046,6 16,6.8954 16,8C16,9.1046 15.1046,10 14,10C12.8954,10 12" +
                    ",9.1046 12,8ZM12,14C12,12.8954 12.8954,12 14,12C15.1046,12 16,12.8954 16,14C16,15.1046 15.1046,1" +
                    "6 14,16C12.8954,16 12,15.1046 12,14ZM12,20C12,18.8954 12.8954,18 14,18C15.1046,18 16,18.8954 16," +
                    "20C16,21.1046 15.1046,22 14,22C12.8954,22 12,21.1046 12,20Z"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 0.9f,
                pathFillType = PathFillType.EvenOdd,
            )
        }.build()
    }

    val DragHandle: ImageVector by lazy {
        ImageVector.Builder(
            name = "apple_queue_draghandle", defaultWidth = 28.dp, defaultHeight = 28.dp,
            viewportWidth = 28f, viewportHeight = 28f,
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "M22,19C22.5523,19 23,19.4477 23,20C23,20.5523 22.5523,21 22,21L6,21C5.4477,21 5,20.5523 5,20C5,1" +
                    "9.4477 5.4477,19 6,19L22,19ZM22,15C22.5523,15 23,15.4477 23,16C23,16.5523 22.5523,17 22,17L6,17C" +
                    "5.4477,17 5,16.5523 5,16C5,15.4477 5.4477,15 6,15L22,15ZM22,11C22.5523,11 23,11.4477 23,12C23,12" +
                    ".5523 22.5523,13 22,13L6,13C5.4477,13 5,12.5523 5,12C5,11.4477 5.4477,11 6,11L22,11ZM22,7C22.552" +
                    "3,7 23,7.4477 23,8C23,8.5523 22.5523,9 22,9L6,9C5.4477,9 5,8.5523 5,8C5,7.4477 5.4477,7 6,7L22,7" +
                    "Z"
                ),
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                pathFillType = PathFillType.EvenOdd,
            )
        }.build()
    }

}
